package fr.antschw.llmproxy.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import fr.antschw.llmproxy.routing.TargetPool;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Serves ready-to-paste client configurations listing every model the proxy currently serves,
 * for clients that need their model list declared in their own configuration file.
 *
 * <ul>
 *     <li>{@code GET /templates/opencode.json}: opencode configuration ({@code ?format=legacy} for
 *         versions using the older {@code provider}/{@code npm}/{@code options} keys)</li>
 *     <li>{@code GET /templates/pi.json}: pi {@code models.json}</li>
 * </ul>
 * The largest known context limit of each model is included ({@code limit.context} for
 * opencode, {@code contextWindow} for pi): the proxy routes long requests to the upstreams able to
 * hold them. The base URL is built from the request's {@code Host} header (and {@code X-Forwarded-Proto}),
 * so it is right however the proxy is reached. The API key is referenced through the
 * {@code LLM_PROXY_LOCAL_KEY} environment variable, never written in the template.
 */
final class TemplatesHandler {

    static final String PROVIDER_ID = "llm-proxy";
    static final String PROVIDER_NAME = "LLM Proxy";
    static final String KEY_VARIABLE = "LLM_PROXY_LOCAL_KEY";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final TargetPool pool;

    TemplatesHandler(TargetPool pool) {
        this.pool = pool;
    }

    /**
     * @param exchange the client exchange
     * @param name     template file name, e.g. {@code opencode.json}
     */
    void handle(HttpExchange exchange, String name) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        boolean legacy = query != null && List.of(query.split("&")).contains("format=legacy");
        String baseUrl = baseUrl(exchange);
        Map<String, Optional<Long>> models = new LinkedHashMap<>();
        pool.modelNames().forEach(model -> models.put(model, pool.largestContextLimit(model)));
        switch (name) {
            case "opencode.json" -> Responses.json(exchange, 200,
                    legacy ? opencodeLegacy(baseUrl, models) : opencode(baseUrl, models));
            case "pi.json" -> Responses.json(exchange, 200, pi(baseUrl, models));
            default -> Responses.error(exchange, 404, "invalid_request_error", "not_found",
                    "Unknown template '" + name + "'. Available: opencode.json, pi.json.");
        }
    }

    static ObjectNode opencode(String baseUrl, Map<String, Optional<Long>> models) {
        ObjectNode root = NODES.objectNode().put("$schema", "https://opencode.ai/config.json");
        ObjectNode provider = root.putObject("providers").putObject(PROVIDER_ID)
                .put("name", PROVIDER_NAME)
                .put("package", "@opencode/ai/providers/openai-compatible");
        provider.putObject("settings")
                .put("baseURL", baseUrl)
                .put("apiKey", "{env:" + KEY_VARIABLE + "}");
        ObjectNode list = provider.putObject("models");
        models.forEach((model, limit) -> {
            ObjectNode entry = list.putObject(model).put("name", model);
            limit.ifPresent(tokens -> entry.putObject("limit").put("context", tokens));
        });
        return root;
    }

    static ObjectNode opencodeLegacy(String baseUrl, Map<String, Optional<Long>> models) {
        ObjectNode root = NODES.objectNode().put("$schema", "https://opencode.ai/config.json");
        ObjectNode provider = root.putObject("provider").putObject(PROVIDER_ID)
                .put("npm", "@ai-sdk/openai-compatible")
                .put("name", PROVIDER_NAME);
        provider.putObject("options")
                .put("baseURL", baseUrl)
                .put("apiKey", "{env:" + KEY_VARIABLE + "}");
        ObjectNode list = provider.putObject("models");
        models.forEach((model, limit) -> {
            ObjectNode entry = list.putObject(model).put("name", model);
            limit.ifPresent(tokens -> entry.putObject("limit").put("context", tokens));
        });
        return root;
    }

    static ObjectNode pi(String baseUrl, Map<String, Optional<Long>> models) {
        ObjectNode root = NODES.objectNode().put("$schema", "https://pi.dev/schemas/models.schema.json");
        ObjectNode provider = root.putObject("providers").putObject(PROVIDER_ID)
                .put("name", PROVIDER_NAME)
                .put("baseUrl", baseUrl)
                .put("api", "openai-completions")
                .put("apiKey", "$" + KEY_VARIABLE);
        ArrayNode list = provider.putArray("models");
        models.forEach((model, limit) -> {
            ObjectNode entry = list.addObject().put("id", model).put("name", model);
            limit.ifPresent(tokens -> entry.put("contextWindow", tokens));
        });
        return root;
    }

    private static String baseUrl(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank()) {
            host = "localhost:" + exchange.getLocalAddress().getPort();
        }
        String proto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        return (proto == null || proto.isBlank() ? "http" : proto.trim()) + "://" + host.trim() + "/v1";
    }
}
