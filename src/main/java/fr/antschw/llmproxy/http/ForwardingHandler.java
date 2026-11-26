package fr.antschw.llmproxy.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import fr.antschw.llmproxy.config.ProxyConfig;
import fr.antschw.llmproxy.routing.ContextEstimator;
import fr.antschw.llmproxy.routing.RetryAfter;
import fr.antschw.llmproxy.routing.Target;
import fr.antschw.llmproxy.routing.TargetPool;
import fr.antschw.llmproxy.routing.TargetPool.Route;
import fr.antschw.llmproxy.routing.TargetPool.Selection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Forwards an OpenAI-compatible request to an upstream, failing over to the next target on
 * rate limits and errors.
 *
 * <p>Any path is forwarded ({@code /chat/completions}, {@code /completions}, {@code /embeddings},
 * {@code /responses}...), so tools relying on other endpoints keep working as long as the
 * upstreams support them. The JSON {@code model} field is rewritten for virtual models; every
 * other field is sent untouched.
 *
 * <p>Failover is only possible before the first byte reaches the client. That is always the case
 * for a 429: the upstream status is known before its body, and the body is streamed only once a
 * target answered with a final status.
 */
final class ForwardingHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ForwardingHandler.class);

    /** Request headers never forwarded: hop-by-hop, recomputed, or the client's own credentials. */
    private static final Set<String> DROPPED_REQUEST_HEADERS = Set.of(
            "host", "connection", "content-length", "transfer-encoding", "keep-alive", "upgrade", "te",
            "trailer", "expect", "proxy-authorization", "proxy-connection", "http2-settings",
            "authorization", "x-api-key", "accept-encoding");

    /** Response headers never copied back: hop-by-hop or recomputed by the server. */
    private static final Set<String> DROPPED_RESPONSE_HEADERS = Set.of(
            "connection", "content-length", "transfer-encoding", "keep-alive", "upgrade", "te", "trailer",
            "proxy-authenticate", "proxy-connection", "date", "server");

    private static final List<String> CONTEXT_OVERFLOW_MARKERS = List.of(
            "context_length_exceeded", "context length", "context window", "maximum context", "too many tokens",
            "prompt is too long", "input is too long", "reduce the length", "max_model_len", "exceeds the model",
            "too long for the model", "string_above_max_length");

    /** Size of an upstream error body kept to return to the client when every attempt failed. */
    private static final int MAX_ERROR_BODY = 64 * 1024;

    private final ProxyConfig config;
    private final TargetPool pool;
    private final HttpClient client;
    private final Clock clock;

    ForwardingHandler(ProxyConfig config, TargetPool pool, HttpClient client, Clock clock) {
        this.config = config;
        this.pool = pool;
        this.client = client;
        this.clock = clock;
    }

    /**
     * @param exchange the client exchange
     * @param apiPath  path relative to the API base, e.g. {@code /chat/completions}
     */
    void handle(HttpExchange exchange, String apiPath) throws IOException {
        Instant start = clock.instant();
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        ObjectNode json = parseJsonObject(body);
        String requestedModel = json != null && json.path("model").isTextual() ? json.get("model").asText() : null;

        Optional<Route> resolved = pool.resolve(requestedModel);
        if (resolved.isEmpty()) {
            if (requestedModel == null) {
                Responses.error(exchange, 400, "invalid_request_error", "missing_model",
                        "The request has no 'model' field and no defaultModel is configured.");
            } else {
                Responses.error(exchange, 404, "invalid_request_error", "model_not_found",
                        "The model '" + requestedModel + "' is not served by this proxy. Available models: "
                                + String.join(", ", pool.modelNames()) + ".");
            }
            return;
        }
        Route route = resolved.get();
        String label = route.name().equals(requestedModel) || requestedModel == null
                ? route.name()
                : requestedModel + " (as " + route.name() + ")";

        long estimatedPrompt = ContextEstimator.promptTokens(json, config.context().charsPerToken());
        long needed = estimatedPrompt + ContextEstimator.requestedOutputTokens(json);
        Set<Target> excluded = new HashSet<>(pool.tooSmallFor(route, needed));
        if (!excluded.isEmpty()) {
            LOG.info("{}: ~{} tokens estimated, skipping {} (context too small)", label, Logs.tokens(needed),
                    excluded.stream().map(t -> t + " (limit " + Logs.tokens(pool.contextLimit(t).orElse(0L)) + ")")
                            .toList());
        }

        ProxyConfig.Retry retry = config.retry();
        Instant waitDeadline = start.plus(retry.maxWaitWhenAllCooling());
        FailedAttempt lastFailure = null;
        int attempt = 0;

        while (attempt < retry.maxAttempts()) {
            Selection selection = pool.acquire(route, excluded);
            if (selection instanceof Selection.Exhausted) {
                break;
            }
            if (selection instanceof Selection.Unavailable(Instant availableAt)) {
                Instant now = clock.instant();
                if (availableAt.isAfter(waitDeadline)) {
                    break;
                }
                Duration wait = Duration.between(now, availableAt);
                LOG.info("{}: all targets cooling down, waiting {}", label, Logs.duration(wait));
                if (!sleep(wait.isNegative() ? Duration.ZERO : wait.plusMillis(5))) {
                    Responses.error(exchange, 503, "server_error", "shutting_down", "The proxy is shutting down.");
                    return;
                }
                continue;
            }
            Target target = ((Selection.Acquired) selection).target();
            attempt++;
            String prefix = label + " -> " + target + " attempt " + attempt + "/" + retry.maxAttempts();

            HttpResponse<InputStream> response;
            try {
                response = client.send(buildRequest(exchange, apiPath, body, json, route, target),
                        HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                Duration cooldown = pool.onRetryableFailure(target, Optional.empty(), false);
                LOG.warn("{}: timeout, cooling down {}", prefix, Logs.duration(cooldown));
                lastFailure = FailedAttempt.network(504, "Upstream " + target.upstream().name() + " timed out.");
                continue;
            } catch (IOException e) {
                Duration cooldown = pool.onRetryableFailure(target, Optional.empty(), false);
                LOG.warn("{}: network error ({}), cooling down {}", prefix, e, Logs.duration(cooldown));
                lastFailure = FailedAttempt.network(502, "Upstream " + target.upstream().name() + " is unreachable.");
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Responses.error(exchange, 503, "server_error", "shutting_down", "The proxy is shutting down.");
                return;
            }

            int status = response.statusCode();
            switch (classify(status)) {
                case RATE_LIMITED, RETRYABLE -> {
                    boolean rateLimited = status == 429;
                    Optional<Duration> hint = RetryAfter.fromHeaders(response.headers(), clock.instant());
                    Duration cooldown = pool.onRetryableFailure(target, hint, rateLimited);
                    LOG.warn("{}: {}, cooling down {}{}", prefix, status, Logs.duration(cooldown),
                            hint.isPresent() ? " (upstream hint)" : "");
                    lastFailure = FailedAttempt.of(response);
                }
                case MISCONFIGURED -> {
                    Duration cooldown = pool.onMisconfigured(target);
                    LOG.error("{}: {} - check the API key of upstream '{}', set aside for {}", prefix, status,
                            target.upstream().name(), Logs.duration(cooldown));
                    lastFailure = FailedAttempt.of(response);
                }
                case CLIENT_ERROR -> {
                    FailedAttempt failure = FailedAttempt.of(response);
                    if (!looksLikeContextOverflow(failure.body())) {
                        LOG.info("{}: {} (client error, not retried)", prefix, status);
                        sendRaw(exchange, status, failure);
                        return;
                    }
                    // Request too long for this upstream: another one may have a larger context.
                    excluded.add(target);
                    LOG.warn("{}: {} context length exceeded (~{} tokens estimated), trying another upstream",
                            prefix, status, Logs.tokens(needed));
                    lastFailure = failure;
                }
                case NOT_FOUND -> {
                    // Model or endpoint unknown to this upstream: try another one, without cooldown,
                    // since the same upstream may serve other requests fine.
                    excluded.add(target);
                    LOG.warn("{}: 404 (unknown model '{}' or unsupported endpoint '{}')", prefix,
                            target.model(), apiPath);
                    lastFailure = FailedAttempt.of(response);
                }
                case PASS_THROUGH -> {
                    if (status < 400) {
                        pool.onSuccess(target);
                    }
                    Duration firstByte = Duration.between(start, clock.instant());
                    UsageObserver usage = new UsageObserver(response.headers().firstValue("Content-Type").orElse(null));
                    stream(exchange, response, target, usage);
                    usage.finish();
                    logOutcome(prefix, status, firstByte, Duration.between(start, clock.instant()), target,
                            estimatedPrompt, usage);
                    return;
                }
            }
        }

        sendFinalFailure(exchange, route, label, attempt, lastFailure);
    }

    private void logOutcome(String prefix, int status, Duration firstByte, Duration total, Target target,
                            long estimatedPrompt, UsageObserver usage) {
        StringBuilder line = new StringBuilder().append(status).append(" in ").append(Logs.duration(total))
                .append(" (first byte ").append(Logs.duration(firstByte)).append(")");
        if (estimatedPrompt > 0) {
            line.append(", ~").append(Logs.tokens(estimatedPrompt)).append(" tokens sent");
        }
        usage.promptTokens().ifPresent(p -> line.append(", upstream counted ").append(Logs.tokens(p)));
        usage.completionTokens().ifPresent(c -> line.append(" + ").append(Logs.tokens(c)).append(" out"));
        usage.finishReason().ifPresent(f -> line.append(", finish=").append(f));
        LOG.info("{}: {}", prefix, line);

        if (status < 300 && usage.promptTokens().isPresent()
                && pool.onUsage(target, estimatedPrompt, usage.promptTokens().get())) {
            LOG.warn("{}: possible silent truncation: ~{} tokens sent but upstream counted {}. The start of the "
                            + "conversation was probably dropped. Measure the real context with "
                            + "'bin/llm-proxy.sh probe {} {}' and set maxContextTokens for '{}'", prefix,
                    Logs.tokens(estimatedPrompt), Logs.tokens(usage.promptTokens().get()), target.upstream().name(),
                    target.model(), target.upstream().name());
        }
        if ("length".equals(usage.finishReason().orElse(null))) {
            LOG.warn("{}: the answer was cut by the output token limit (finish=length)", prefix);
        }
    }

    /** Recognises the usual "context too long" errors of OpenAI-compatible servers. */
    static boolean looksLikeContextOverflow(byte[] body) {
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return CONTEXT_OVERFLOW_MARKERS.stream().anyMatch(text::contains);
    }

    private void sendFinalFailure(HttpExchange exchange, Route route, String label, int attempts,
                                  FailedAttempt lastFailure) throws IOException {
        Duration retryAfter = Duration.between(clock.instant(), pool.earliestAvailability(route));
        if (lastFailure == null || lastFailure.status() == 429) {
            LOG.warn("{}: no target available after {} attempt(s), answering 429", label, attempts);
            if (lastFailure != null && lastFailure.body().length > 0) {
                long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
                exchange.getResponseHeaders().set("Retry-After", Long.toString(seconds));
                sendRaw(exchange, 429, lastFailure);
            } else {
                Responses.rateLimited(exchange, retryAfter,
                        "Every upstream serving '" + route.name() + "' is rate limited. Retry later.");
            }
            return;
        }
        LOG.warn("{}: giving up after {} attempt(s), last status {}", label, attempts, lastFailure.status());
        if (lastFailure.body().length > 0) {
            sendRaw(exchange, lastFailure.status(), lastFailure);
        } else {
            Responses.error(exchange, lastFailure.status(), "server_error", "upstream_error", lastFailure.message());
        }
    }

    private static void sendRaw(HttpExchange exchange, int status, FailedAttempt failure) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", failure.contentType());
        exchange.sendResponseHeaders(status, failure.body().length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(failure.body());
        }
    }

    private HttpRequest buildRequest(HttpExchange exchange, String apiPath, byte[] body, ObjectNode json,
                                     Route route, Target target) throws IOException {
        byte[] payload = body;
        if (json != null && route.virtual()) {
            ObjectNode copy = json.deepCopy();
            copy.put("model", target.model());
            payload = Responses.JSON.writeValueAsBytes(copy);
        }
        String base = target.upstream().baseUrl().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String query = exchange.getRequestURI().getRawQuery();
        URI uri = URI.create(base + apiPath + (query == null ? "" : "?" + query));

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(config.timeouts().request());
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (!DROPPED_REQUEST_HEADERS.contains(name)) {
                header.getValue().forEach(value -> builder.header(header.getKey(), value));
            }
        }
        target.upstream().headers().forEach(builder::setHeader);
        builder.setHeader("Authorization", "Bearer " + target.upstream().apiKey());

        HttpRequest.BodyPublisher publisher = payload.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(payload);
        return builder.method(exchange.getRequestMethod(), publisher).build();
    }

    /**
     * Copies the upstream response to the client, flushing after each read so server-sent events
     * reach the client as soon as the upstream produces them.
     */
    private static void stream(HttpExchange exchange, HttpResponse<InputStream> response, Target target,
                               UsageObserver usage) throws IOException {
        response.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.startsWith(":") && !DROPPED_RESPONSE_HEADERS.contains(lower)) {
                exchange.getResponseHeaders().put(name, values);
            }
        });
        exchange.getResponseHeaders().set("X-LLM-Proxy-Target", target.id());

        int status = response.statusCode();
        boolean noBody = "HEAD".equalsIgnoreCase(exchange.getRequestMethod()) || status == 204 || status == 304;
        try (InputStream in = response.body()) {
            exchange.sendResponseHeaders(status, noBody ? -1 : 0);
            if (noBody) {
                return;
            }
            OutputStream out = exchange.getResponseBody();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                try {
                    out.write(buffer, 0, read);
                    out.flush();
                    usage.accept(buffer, read);
                } catch (IOException e) {
                    // Closing the upstream stream (try-with-resources) cancels the upstream request.
                    LOG.info("Client disconnected from {} stream", target);
                    return;
                }
            }
            out.close();
        }
    }

    private static ObjectNode parseJsonObject(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode node = Responses.JSON.readTree(body);
            return node instanceof ObjectNode object ? object : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static Outcome classify(int status) {
        return switch (status) {
            case 429 -> Outcome.RATE_LIMITED;
            case 401, 403 -> Outcome.MISCONFIGURED;
            case 404 -> Outcome.NOT_FOUND;
            case 408, 500, 502, 503, 504 -> Outcome.RETRYABLE;
            case 400, 413 -> Outcome.CLIENT_ERROR;
            default -> status >= 520 && status <= 599 ? Outcome.RETRYABLE : Outcome.PASS_THROUGH;
        };
    }

    /** What to do with an upstream status. */
    enum Outcome {
        /** 429: cooldown and try the next target. */
        RATE_LIMITED,
        /** Transient server error: cooldown and try the next target. */
        RETRYABLE,
        /** Bad credentials: long cooldown and try the next target. */
        MISCONFIGURED,
        /** 400/413: retried elsewhere only if the body says the context is too long. */
        CLIENT_ERROR,
        /** Unknown model or endpoint on this upstream: try another target for this request only. */
        NOT_FOUND,
        /** Success, or a client error the client must see (e.g. 400): send it as is. */
        PASS_THROUGH
    }

    /** An upstream failure kept to answer the client if every attempt fails. */
    private record FailedAttempt(int status, String contentType, byte[] body, String message) {

        static FailedAttempt network(int status, String message) {
            return new FailedAttempt(status, "application/json", new byte[0], message);
        }

        static FailedAttempt of(HttpResponse<InputStream> response) {
            byte[] body;
            try (InputStream in = response.body()) {
                body = in.readNBytes(MAX_ERROR_BODY);
            } catch (IOException e) {
                body = new byte[0];
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("application/json");
            return new FailedAttempt(response.statusCode(), contentType, body,
                    "Upstream answered " + response.statusCode() + ".");
        }
    }
}
