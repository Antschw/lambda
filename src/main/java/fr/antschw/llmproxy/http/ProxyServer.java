package fr.antschw.llmproxy.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.antschw.llmproxy.config.ProxyConfig;
import fr.antschw.llmproxy.routing.ModelDiscovery;
import fr.antschw.llmproxy.routing.TargetPool;
import fr.antschw.llmproxy.routing.TargetPool.TargetStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The HTTP server exposing an OpenAI-compatible API.
 *
 * <p>Routes:
 * <ul>
 *     <li>{@code GET /health}: target health, without authentication (for container health checks)</li>
 *     <li>{@code GET /v1/models}, {@code GET /v1/models/{id}}: models served by the proxy</li>
 *     <li>{@code GET /templates/{name}}: ready-to-paste client configurations</li>
 *     <li>anything else under {@code /v1/}: forwarded to an upstream with failover</li>
 * </ul>
 * The {@code /v1} prefix is optional, because some tools add it to the base URL and others do not.
 * Each request runs on its own virtual thread.
 *
 * <p>Model lists are discovered from the upstreams before the server accepts requests, then
 * refreshed periodically ({@code discovery.refreshInterval}).
 */
public final class ProxyServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyServer.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ProxyConfig config;
    private final TargetPool pool;
    private final Auth auth;
    private final ForwardingHandler forwarding;
    private final TemplatesHandler templates;
    private final ModelDiscovery discovery;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("model-discovery").daemon().factory());
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client;
    private HttpServer server;

    /**
     * @param config validated configuration
     * @param clock  time source
     */
    public ProxyServer(ProxyConfig config, Clock clock) {
        this.config = config;
        this.pool = new TargetPool(config, clock);
        this.auth = new Auth(config.localApiKeys());
        this.client = HttpClient.newBuilder()
                .connectTimeout(config.timeouts().connect())
                .followRedirects(HttpClient.Redirect.NEVER)
                // Honours -Dhttps.proxyHost / -Dhttps.proxyPort for corporate networks.
                .proxy(ProxySelector.getDefault())
                .executor(executor)
                .build();
        this.forwarding = new ForwardingHandler(config, pool, client, clock);
        this.templates = new TemplatesHandler(pool);
        this.discovery = new ModelDiscovery(config, client);
    }

    /**
     * Starts listening.
     *
     * @return this server
     * @throws IOException if the address cannot be bound
     */
    public ProxyServer start() throws IOException {
        refreshModels();
        Duration interval = config.discovery().refreshInterval();
        if (!interval.isZero() && config.upstreams().stream().anyMatch(ProxyConfig.Upstream::discoverModels)) {
            scheduler.scheduleWithFixedDelay(this::refreshModelsSafely, interval.toMillis(), interval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
        ProxyConfig.Listen listen = config.listen();
        server = HttpServer.create(new InetSocketAddress(listen.host(), listen.port()), 0);
        server.createContext("/", this::dispatch);
        server.setExecutor(executor);
        server.start();
        return this;
    }

    /** @return the bound port (useful when the configured port is 0) */
    public int port() {
        return server.getAddress().getPort();
    }

    /** @return the routing pool, for inspection */
    public TargetPool pool() {
        return pool;
    }

    /** Reads the upstreams' model lists now and updates the routes. */
    public void refreshModels() {
        List<String> before = pool.modelNames();
        pool.updateModels(discovery.discover(), discovery.advertisedLimits());
        List<String> after = pool.modelNames();
        if (!after.equals(before)) {
            List<String> added = after.stream().filter(m -> !before.contains(m)).toList();
            List<String> removed = before.stream().filter(m -> !after.contains(m)).toList();
            LOG.info("Models updated: {} served{}{}", after.size(),
                    added.isEmpty() ? "" : ", added " + added,
                    removed.isEmpty() ? "" : ", removed " + removed);
        }
    }

    private void refreshModelsSafely() {
        try {
            refreshModels();
        } catch (RuntimeException e) {
            LOG.warn("Model refresh failed", e);
        }
    }

    /** Stops accepting requests and waits up to the configured grace period for in-flight ones. */
    @Override
    public void close() {
        scheduler.shutdownNow();
        if (server != null) {
            server.stop((int) Math.max(0, config.timeouts().shutdown().toSeconds()));
        }
        client.close();
        executor.shutdownNow();
    }

    private void dispatch(HttpExchange exchange) {
        MDC.put("req", Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFL));
        try {
            String path = exchange.getRequestURI().getRawPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String method = exchange.getRequestMethod();
            LOG.debug("{} {}", method, path);

            if (path.equals("/health")) {
                health(exchange);
                return;
            }
            if (!auth.isAuthorized(exchange.getRequestHeaders())) {
                LOG.warn("Rejected {} {}: invalid or missing local API key", method, path);
                Responses.error(exchange, 401, "invalid_request_error", "invalid_api_key",
                        "Invalid or missing API key for this proxy.");
                return;
            }

            String apiPath = path.equals("/v1") ? "/" : path.startsWith("/v1/") ? path.substring(3) : path;
            if (method.equals("GET") && path.startsWith("/templates/")) {
                templates.handle(exchange, path.substring("/templates/".length()));
            } else if (method.equals("GET") && apiPath.equals("/models")) {
                listModels(exchange);
            } else if (method.equals("GET") && apiPath.startsWith("/models/")) {
                getModel(exchange, apiPath.substring("/models/".length()));
            } else {
                forwarding.handle(exchange, apiPath);
            }
        } catch (Exception e) {
            LOG.error("Unexpected error while handling {} {}", exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(), e);
            if (exchange.getResponseCode() == -1) {
                try {
                    Responses.error(exchange, 500, "server_error", "proxy_error", "Internal proxy error.");
                } catch (IOException ignored) {
                    // The client is gone.
                }
            }
        } finally {
            exchange.close();
            MDC.clear();
        }
    }

    private void listModels(HttpExchange exchange) throws IOException {
        ObjectNode body = NODES.objectNode().put("object", "list");
        ArrayNode data = body.putArray("data");
        pool.modelNames().forEach(name -> data.add(model(name)));
        Responses.json(exchange, 200, body);
    }

    private void getModel(HttpExchange exchange, String id) throws IOException {
        String name = java.net.URLDecoder.decode(id, java.nio.charset.StandardCharsets.UTF_8);
        if (pool.modelNames().contains(name)) {
            Responses.json(exchange, 200, model(name));
        } else {
            Responses.error(exchange, 404, "invalid_request_error", "model_not_found",
                    "The model '" + name + "' is not served by this proxy.");
        }
    }

    private ObjectNode model(String name) {
        ObjectNode node = NODES.objectNode()
                .put("id", name)
                .put("object", "model")
                .put("created", 0)
                .put("owned_by", "llm-proxy");
        // Non-standard but harmless for clients: shows where the model is routed.
        ArrayNode upstreams = node.putArray("upstreams");
        pool.upstreamsOf(name).forEach(upstreams::add);
        pool.largestContextLimit(name).ifPresent(limit -> node.put("context_length", limit));
        return node;
    }

    private void health(HttpExchange exchange) throws IOException {
        Map<String, List<TargetStatus>> status = pool.status();
        boolean allRoutesUsable = status.values().stream()
                .allMatch(targets -> targets.stream().anyMatch(TargetStatus::available));
        ObjectNode body = NODES.objectNode().put("status", allRoutesUsable ? "ok" : "degraded");
        ObjectNode models = body.putObject("models");
        status.forEach((model, targets) -> {
            ArrayNode list = models.putArray(model);
            for (TargetStatus t : targets) {
                ObjectNode node = list.addObject()
                        .put("target", t.target())
                        .put("available", t.available())
                        .put("consecutiveFailures", t.consecutiveFailures())
                        .put("misconfigured", t.misconfigured())
                        .put("requests", t.requests())
                        .put("successes", t.successes())
                        .put("rateLimited", t.rateLimited())
                        .put("failures", t.failures())
                        .put("maxPromptTokensSeen", t.maxPromptTokensSeen())
                        .put("truncationsSuspected", t.truncationsSuspected());
                t.contextLimit().ifPresent(limit -> node.put("contextLimit", limit));
                if (t.cooldownUntil() != null) {
                    node.put("cooldownUntil", t.cooldownUntil().toString());
                }
            }
        });
        // Always 200: a degraded state is temporary and must not get the container restarted.
        Responses.json(exchange, 200, body);
    }
}
