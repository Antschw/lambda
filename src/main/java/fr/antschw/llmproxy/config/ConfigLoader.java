package fr.antschw.llmproxy.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and validates the JSON configuration file.
 *
 * <p>Any string value may reference environment variables with {@code ${NAME}} or
 * {@code ${NAME:-default}}, so secrets stay out of the file and the same file works
 * on a workstation and in a container.
 */
public final class ConfigLoader {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Function<String, String> environment;

    /** Creates a loader that reads the process environment. */
    public ConfigLoader() {
        this(System::getenv);
    }

    /**
     * Creates a loader with a custom variable source (used by tests).
     *
     * @param environment returns a variable's value, or {@code null} when unset
     */
    public ConfigLoader(Function<String, String> environment) {
        this.environment = environment;
    }

    /**
     * Loads a configuration file.
     *
     * @param file path to the JSON file
     * @return the validated configuration
     * @throws IOException          if the file cannot be read
     * @throws ConfigException      if the content is invalid
     */
    public ProxyConfig load(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    /**
     * Parses configuration JSON.
     *
     * @param json the JSON text
     * @return the validated configuration
     * @throws ConfigException if the content is invalid
     */
    public ProxyConfig parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ConfigException("Configuration is not valid JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw new ConfigException("Configuration must be a JSON object");
        }

        JsonNode listenNode = root.path("listen");
        ProxyConfig.Listen listen = new ProxyConfig.Listen(
                text(listenNode, "host", "127.0.0.1"),
                integer(listenNode, "port", 4000));
        if (listen.port() < 0 || listen.port() > 65_535) {
            throw new ConfigException("listen.port must be between 0 and 65535");
        }

        Set<String> localKeys = new LinkedHashSet<>();
        for (JsonNode key : root.path("localApiKeys")) {
            String value = substitute(key.asText()).trim();
            if (!value.isEmpty()) {
                localKeys.add(value);
            }
        }

        List<ProxyConfig.Upstream> upstreams = parseUpstreams(root.path("upstreams"));
        Set<String> upstreamNames = new HashSet<>();
        upstreams.forEach(u -> upstreamNames.add(u.name()));

        Map<String, List<ProxyConfig.ModelRoute>> models = parseModels(root.path("models"), upstreamNames);
        if (models.isEmpty() && upstreams.stream().allMatch(u -> u.passthroughModels().isEmpty())) {
            throw new ConfigException("Define at least one virtual model in 'models' or one 'passthroughModels' entry");
        }

        Optional<String> defaultModel = Optional.ofNullable(textOrNull(root, "defaultModel"));
        defaultModel.ifPresent(name -> {
            if (!models.containsKey(name)) {
                throw new ConfigException("defaultModel '" + name + "' is not a virtual model defined in 'models'");
            }
        });

        JsonNode retryNode = root.path("retry");
        ProxyConfig.Retry retry = new ProxyConfig.Retry(
                integer(retryNode, "maxAttempts", 6),
                duration(retryNode, "defaultCooldown", "30s"),
                duration(retryNode, "maxCooldown", "5m"),
                duration(retryNode, "misconfiguredCooldown", "10m"),
                duration(retryNode, "maxWaitWhenAllCooling", "60s"));
        if (retry.maxAttempts() < 1) {
            throw new ConfigException("retry.maxAttempts must be at least 1");
        }

        JsonNode timeoutNode = root.path("timeouts");
        ProxyConfig.Timeouts timeouts = new ProxyConfig.Timeouts(
                duration(timeoutNode, "connect", "10s"),
                duration(timeoutNode, "request", "10m"),
                duration(timeoutNode, "shutdown", "10s"));

        return new ProxyConfig(listen, localKeys, upstreams, models, defaultModel, retry, timeouts);
    }

    private List<ProxyConfig.Upstream> parseUpstreams(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw new ConfigException("'upstreams' must be a non-empty array");
        }
        List<ProxyConfig.Upstream> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode item : node) {
            String name = required(item, "name", "upstreams[]");
            if (!names.add(name)) {
                throw new ConfigException("Duplicate upstream name: " + name);
            }
            String where = "upstream '" + name + "'";
            URI baseUrl = uri(required(item, "baseUrl", where), where);
            String apiKey = required(item, "apiKey", where);

            Map<String, String> headers = new LinkedHashMap<>();
            item.path("headers").properties().forEach(e -> headers.put(e.getKey(), substitute(e.getValue().asText())));

            List<String> passthrough = new ArrayList<>();
            item.path("passthroughModels").forEach(m -> passthrough.add(substitute(m.asText())));

            Optional<Integer> rpm = item.hasNonNull("maxRequestsPerMinute")
                    ? Optional.of(integer(item, "maxRequestsPerMinute", 0))
                    : Optional.empty();
            if (rpm.isPresent() && rpm.get() < 1) {
                throw new ConfigException(where + ": maxRequestsPerMinute must be at least 1");
            }
            result.add(new ProxyConfig.Upstream(name, baseUrl, apiKey, headers, passthrough, rpm));
        }
        return result;
    }

    private Map<String, List<ProxyConfig.ModelRoute>> parseModels(JsonNode node, Set<String> upstreamNames) {
        Map<String, List<ProxyConfig.ModelRoute>> result = new LinkedHashMap<>();
        if (node.isMissingNode() || node.isNull()) {
            return result;
        }
        if (!node.isObject()) {
            throw new ConfigException("'models' must be an object mapping virtual model names to routes");
        }
        node.properties().forEach(entry -> {
            String virtualModel = entry.getKey();
            JsonNode routes = entry.getValue();
            if (!routes.isArray() || routes.isEmpty()) {
                throw new ConfigException("Virtual model '" + virtualModel + "' must have a non-empty array of routes");
            }
            List<ProxyConfig.ModelRoute> list = new ArrayList<>();
            for (JsonNode route : routes) {
                String where = "virtual model '" + virtualModel + "'";
                String upstream = required(route, "upstream", where);
                if (!upstreamNames.contains(upstream)) {
                    throw new ConfigException(where + " references unknown upstream '" + upstream + "'");
                }
                list.add(new ProxyConfig.ModelRoute(upstream, required(route, "model", where)));
            }
            result.put(virtualModel, list);
        });
        return result;
    }

    /**
     * Replaces {@code ${NAME}} and {@code ${NAME:-default}} references.
     *
     * @param value raw value
     * @return value with variables resolved
     * @throws ConfigException if a variable without default is not set
     */
    String substitute(String value) {
        Matcher matcher = VARIABLE.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String fallback = matcher.group(2);
            String resolved = environment.apply(name);
            if (resolved == null || resolved.isEmpty()) {
                if (fallback == null) {
                    throw new ConfigException("Environment variable '" + name + "' is not set");
                }
                resolved = fallback;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String required(JsonNode node, String field, String where) {
        String value = textOrNull(node, field);
        if (value == null || value.isBlank()) {
            throw new ConfigException(where + ": '" + field + "' is required");
        }
        return value;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : substitute(value.asText());
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = textOrNull(node, field);
        return value == null || value.isBlank() ? fallback : value;
    }

    private int integer(JsonNode node, String field, int fallback) {
        String value = textOrNull(node, field);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("'" + field + "' must be an integer, got '" + value + "'");
        }
    }

    private Duration duration(JsonNode node, String field, String fallback) {
        String value = text(node, field, fallback);
        try {
            return Durations.parse(value);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("'" + field + "': " + e.getMessage());
        }
    }

    private static URI uri(String value, String where) {
        try {
            URI uri = new URI(value);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw new ConfigException(where + ": baseUrl must start with http:// or https://");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new ConfigException(where + ": invalid baseUrl '" + value + "'");
        }
    }
}
