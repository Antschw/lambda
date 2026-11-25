package fr.antschw.llmproxy.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, validated proxy configuration.
 *
 * @param listen                 address the proxy binds to
 * @param localApiKeys           keys accepted from clients; empty means no client authentication
 * @param upstreams              upstream providers, by declaration order
 * @param models                 virtual model name to the list of real targets serving it
 * @param defaultModel           virtual model used when a request has no or an unknown model, if any
 * @param retry                  failover and cooldown tuning
 * @param timeouts               network timeouts
 * @param discovery              automatic model discovery tuning
 */
public record ProxyConfig(
        Listen listen,
        Set<String> localApiKeys,
        List<Upstream> upstreams,
        Map<String, List<ModelRoute>> models,
        Optional<String> defaultModel,
        Retry retry,
        Timeouts timeouts,
        Discovery discovery) {

    public ProxyConfig {
        Objects.requireNonNull(listen, "listen");
        localApiKeys = Set.copyOf(localApiKeys);
        upstreams = List.copyOf(upstreams);
        models = Map.copyOf(models);
        Objects.requireNonNull(defaultModel, "defaultModel");
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(timeouts, "timeouts");
        Objects.requireNonNull(discovery, "discovery");
    }

    /**
     * Address the HTTP server binds to.
     *
     * @param host host or IP, e.g. {@code 127.0.0.1} locally or {@code 0.0.0.0} in a container
     * @param port TCP port
     */
    public record Listen(String host, int port) {
    }

    /**
     * An OpenAI-compatible provider.
     *
     * @param name                 unique, human-readable name used in logs and routes
     * @param baseUrl              base URL including the API version, e.g. {@code https://api.example.com/v1}
     * @param apiKey               key sent as {@code Authorization: Bearer}
     * @param headers              extra headers sent with every request to this provider
     * @param passthroughModels    real model names the client may request directly, always routed
     * @param maxRequestsPerMinute optional proactive limit, so the proxy avoids hitting the provider's 429
     * @param discoverModels       whether to read the provider's model list from {@code GET {baseUrl}/models}
     * @param includeModels        glob patterns a discovered model must match (empty means all)
     * @param excludeModels        glob patterns of discovered models to ignore
     */
    public record Upstream(
            String name,
            URI baseUrl,
            String apiKey,
            Map<String, String> headers,
            List<String> passthroughModels,
            Optional<Integer> maxRequestsPerMinute,
            boolean discoverModels,
            List<String> includeModels,
            List<String> excludeModels) {

        public Upstream {
            headers = Map.copyOf(headers);
            passthroughModels = List.copyOf(passthroughModels);
            includeModels = List.copyOf(includeModels);
            excludeModels = List.copyOf(excludeModels);
        }

        /** Never print the key. */
        @Override
        public String toString() {
            return "Upstream[" + name + " " + baseUrl + "]";
        }
    }

    /**
     * One real model on one upstream that serves a virtual model.
     *
     * @param upstream name of the upstream
     * @param model    model name as the upstream knows it
     */
    public record ModelRoute(String upstream, String model) {
    }

    /**
     * Failover and cooldown tuning.
     *
     * @param maxAttempts           maximum upstream calls for one client request
     * @param defaultCooldown       cooldown after a failure when the upstream gives no hint; doubled on each consecutive failure
     * @param maxCooldown           upper bound for any cooldown, including upstream hints
     * @param misconfiguredCooldown cooldown after 401/403/404 (bad key, wrong URL or model)
     * @param maxWaitWhenAllCooling how long a request may wait for a target to become available
     */
    public record Retry(
            int maxAttempts,
            Duration defaultCooldown,
            Duration maxCooldown,
            Duration misconfiguredCooldown,
            Duration maxWaitWhenAllCooling) {
    }

    /**
     * Network timeouts.
     *
     * @param connect  TCP/TLS connection timeout to upstreams
     * @param request  time allowed until upstream response headers arrive (streaming bodies are not limited)
     * @param shutdown grace period for in-flight requests on shutdown
     */
    public record Timeouts(Duration connect, Duration request, Duration shutdown) {
    }

    /**
     * Automatic model discovery.
     *
     * @param refreshInterval how often model lists are read again; zero disables refreshes after startup
     * @param timeout         time allowed for one provider to answer {@code GET /models}
     */
    public record Discovery(Duration refreshInterval, Duration timeout) {
    }
}
