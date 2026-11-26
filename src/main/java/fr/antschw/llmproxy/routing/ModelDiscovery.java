package fr.antschw.llmproxy.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.antschw.llmproxy.config.ProxyConfig;
import fr.antschw.llmproxy.config.ProxyConfig.Upstream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Reads the model list of every upstream that has {@code discoverModels} enabled, through the
 * standard OpenAI endpoint {@code GET {baseUrl}/models}.
 *
 * <p>Duplicate ids in an answer are collapsed, so an upstream listing the same model twice is
 * not used twice as often. When an upstream cannot be read, its last known list is kept, so a
 * temporary outage of the model list does not remove models from routing. Thread-safe.
 */
public final class ModelDiscovery {

    private static final Logger LOG = LoggerFactory.getLogger(ModelDiscovery.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProxyConfig config;
    private final HttpClient client;
    private final Map<String, Set<String>> lastKnown = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> lastKnownLimits = new ConcurrentHashMap<>();

    /** Fields of a {@code /models} entry that may carry the model's context size. */
    private static final List<String> CONTEXT_FIELDS =
            List.of("context_length", "context_window", "max_model_len", "max_context_length");

    /**
     * @param config validated configuration
     * @param client HTTP client used to call upstreams
     */
    public ModelDiscovery(ProxyConfig config, HttpClient client) {
        this.config = config;
        this.client = client;
    }

    /**
     * Reads every discoverable upstream in parallel.
     *
     * @return model ids by upstream name, for upstreams with discovery enabled that were read at
     *         least once
     */
    public Map<String, Set<String>> discover() {
        List<Upstream> upstreams = config.upstreams().stream().filter(Upstream::discoverModels).toList();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (Upstream upstream : upstreams) {
                tasks.add(executor.submit(() -> refresh(upstream)));
            }
            for (Future<?> task : tasks) {
                try {
                    task.get();
                } catch (ExecutionException e) {
                    LOG.warn("Model discovery failed: {}", e.getCause().toString());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        upstreams.forEach(u -> {
            Set<String> models = lastKnown.get(u.name());
            if (models != null) {
                result.put(u.name(), models);
            }
        });
        return result;
    }

    /**
     * @return context sizes advertised in the last successful {@code /models} answers, by upstream
     *         name then model id
     */
    public Map<String, Map<String, Integer>> advertisedLimits() {
        return Map.copyOf(lastKnownLimits);
    }

    private void refresh(Upstream upstream) {
        String base = upstream.baseUrl().toString();
        URI uri = URI.create((base.endsWith("/") ? base : base + "/") + "models");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(config.discovery().timeout())
                .header("Accept", "application/json");
        upstream.headers().forEach(builder::setHeader);
        builder.setHeader("Authorization", "Bearer " + upstream.apiKey());

        HttpResponse<byte[]> response;
        try {
            response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            keepPrevious(upstream, "unreachable (" + e + ")");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (response.statusCode() != 200) {
            keepPrevious(upstream, "answered " + response.statusCode());
            return;
        }

        List<String> ids;
        JsonNode root;
        try {
            root = JSON.readTree(response.body());
            ids = parseIds(root);
        } catch (IOException e) {
            keepPrevious(upstream, "returned invalid JSON");
            return;
        }
        if (ids == null) {
            keepPrevious(upstream, "returned no 'data' array");
            return;
        }

        Set<String> unique = new TreeSet<>(ids);
        ModelFilter filter = new ModelFilter(upstream.includeModels(), upstream.excludeModels());
        Set<String> kept = new TreeSet<>(unique.stream().filter(filter).toList());
        if (unique.size() < ids.size()) {
            LOG.debug("Upstream '{}' listed {} duplicate model id(s), each one is used once",
                    upstream.name(), ids.size() - unique.size());
        }
        Map<String, Integer> limits = new java.util.HashMap<>(parseLimits(root));
        limits.keySet().retainAll(kept);
        lastKnownLimits.put(upstream.name(), Map.copyOf(limits));
        Set<String> previous = lastKnown.put(upstream.name(), Set.copyOf(kept));
        if (!kept.equals(previous)) {
            LOG.info("Upstream '{}': {} model(s) discovered{}", upstream.name(), kept.size(),
                    unique.size() > kept.size() ? " (" + (unique.size() - kept.size()) + " filtered out)" : "");
        }
    }

    private void keepPrevious(Upstream upstream, String reason) {
        Set<String> previous = lastKnown.get(upstream.name());
        LOG.warn("Cannot read the model list of upstream '{}': {}; {}", upstream.name(), reason,
                previous == null ? "only its configured models are served" : "keeping its last known list");
    }

    /**
     * Accepts the OpenAI format {@code {"data": [{"id": ...}]}} and, for lenient servers,
     * {@code {"models": [...]}} or a bare array, with objects ({@code id} or {@code name}) or strings.
     *
     * @return the ids, or {@code null} when the document has no model array
     */
    static List<String> parseIds(JsonNode root) {
        JsonNode array = modelArray(root);
        if (array == null) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode item : array) {
            JsonNode id = item.isTextual() ? item : item.has("id") ? item.get("id") : item.get("name");
            if (id != null && id.isTextual() && !id.asText().isBlank()) {
                ids.add(id.asText());
            }
        }
        return ids;
    }

    /**
     * Reads the context size advertised for each model, if any ({@code context_length},
     * {@code context_window}, {@code max_model_len} or {@code max_context_length}). When a model
     * is listed several times, the largest value wins.
     *
     * @return context size by model id
     */
    static Map<String, Integer> parseLimits(JsonNode root) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        JsonNode array = modelArray(root);
        if (array == null) {
            return limits;
        }
        for (JsonNode item : array) {
            JsonNode id = item.has("id") ? item.get("id") : item.get("name");
            if (id == null || !id.isTextual()) {
                continue;
            }
            for (String field : CONTEXT_FIELDS) {
                JsonNode value = item.get(field);
                if (value != null && value.canConvertToInt() && value.asInt() > 0) {
                    limits.merge(id.asText(), value.asInt(), Math::max);
                    break;
                }
            }
        }
        return limits;
    }

    private static JsonNode modelArray(JsonNode root) {
        JsonNode array = root.isArray() ? root : root.has("data") ? root.get("data") : root.get("models");
        return array != null && array.isArray() ? array : null;
    }
}
