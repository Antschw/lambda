package fr.antschw.llmproxy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    private static final String MINIMAL = """
            {
              "upstreams": [{"name": "a", "baseUrl": "https://a.example/v1", "apiKey": "${KEY_A}"}],
              "models": {"coder": [{"upstream": "a", "model": "real-model"}]}
            }""";

    private final ConfigLoader loader = new ConfigLoader(Map.of("KEY_A", "secret-a", "PORT", "8080")::get);

    @Test
    void appliesDefaults() {
        ProxyConfig config = loader.parse(MINIMAL);

        assertThat(config.listen()).isEqualTo(new ProxyConfig.Listen("127.0.0.1", 4000));
        assertThat(config.localApiKeys()).isEmpty();
        assertThat(config.defaultModel()).isEmpty();
        assertThat(config.retry().maxAttempts()).isEqualTo(6);
        assertThat(config.retry().defaultCooldown()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.retry().maxCooldown()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.retry().maxWaitWhenAllCooling()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.timeouts().request()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.upstreams().getFirst().apiKey()).isEqualTo("secret-a");
        assertThat(config.models().get("coder")).containsExactly(new ProxyConfig.ModelRoute("a", "real-model"));
        assertThat(config.discovery().refreshInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(config.discovery().timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.upstreams().getFirst().discoverModels()).isTrue();
        assertThat(config.upstreams().getFirst().excludeModels()).isEmpty();
        assertThat(config.upstreams().getFirst().maxContextTokens()).isEmpty();
        assertThat(config.context()).isEqualTo(new ProxyConfig.Context(3.5, 0.6, false));
    }

    @Test
    void readsContextLimitsInOrder() {
        ProxyConfig config = loader.parse("""
                {
                  "upstreams": [{"name": "a", "baseUrl": "https://a/v1", "apiKey": "k", "maxContextTokens": 32000,
                                 "modelContextTokens": {"z-*": 8000, "a-*": 128000}}],
                  "context": {"charsPerToken": 3, "truncationRatio": 0.5, "learnFromTruncation": true}
                }""");

        ProxyConfig.Upstream upstream = config.upstreams().getFirst();
        assertThat(upstream.maxContextTokens()).hasValue(32_000);
        assertThat(upstream.modelContextTokens().keySet()).containsExactly("z-*", "a-*");
        assertThat(config.context()).isEqualTo(new ProxyConfig.Context(3.0, 0.5, true));
    }

    @Test
    void acceptsUpstreamsWithOnlyDiscoveryAndReadsFilters() {
        ProxyConfig config = loader.parse("""
                {
                  "upstreams": [{"name": "a", "baseUrl": "https://a/v1", "apiKey": "k",
                                 "includeModels": ["gpt-*"], "excludeModels": ["*mini", "${UNSET:-}"]}],
                  "discovery": {"refreshInterval": "0", "timeout": "3s"}
                }""");

        assertThat(config.models()).isEmpty();
        assertThat(config.upstreams().getFirst().includeModels()).containsExactly("gpt-*");
        assertThat(config.upstreams().getFirst().excludeModels()).containsExactly("*mini");
        assertThat(config.discovery().refreshInterval()).isZero();
        assertThat(config.discovery().timeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void substitutesEnvironmentVariablesWithDefaults() {
        ProxyConfig config = loader.parse("""
                {
                  "listen": {"host": "${HOST:-0.0.0.0}", "port": "${PORT}"},
                  "localApiKeys": ["${LOCAL_KEY:-}", "fixed-key"],
                  "upstreams": [{"name": "a", "baseUrl": "https://a.example/v1", "apiKey": "${KEY_A}",
                                 "headers": {"X-Org": "${ORG:-none}"}, "maxRequestsPerMinute": 20}],
                  "models": {"coder": [{"upstream": "a", "model": "m"}]},
                  "defaultModel": "coder",
                  "retry": {"defaultCooldown": "PT10S", "maxAttempts": 3}
                }""");

        assertThat(config.listen()).isEqualTo(new ProxyConfig.Listen("0.0.0.0", 8080));
        assertThat(config.localApiKeys()).containsExactly("fixed-key");
        assertThat(config.upstreams().getFirst().headers()).containsEntry("X-Org", "none");
        assertThat(config.upstreams().getFirst().maxRequestsPerMinute()).hasValue(20);
        assertThat(config.defaultModel()).hasValue("coder");
        assertThat(config.retry().defaultCooldown()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.retry().maxAttempts()).isEqualTo(3);
    }

    @Test
    void neverPrintsApiKeys() {
        ProxyConfig config = loader.parse(MINIMAL);

        assertThat(config.toString()).doesNotContain("secret-a");
    }

    @Test
    void loadsTheExampleFile() throws IOException {
        ConfigLoader exampleLoader = new ConfigLoader(name -> name.endsWith("_API_KEY") ? "key-" + name : null);

        ProxyConfig config = exampleLoader.load(Path.of("config.example.json"));

        assertThat(config.upstreams()).hasSize(3);
        assertThat(config.models()).containsKey("coder");
        assertThat(config.listen()).isEqualTo(new ProxyConfig.Listen("127.0.0.1", 4000));
        assertThat(config.localApiKeys()).isEmpty();
    }

    static Stream<Arguments> invalidConfigurations() {
        String upstream = "{\"name\": \"a\", \"baseUrl\": \"https://a/v1\", \"apiKey\": \"k\"";
        String passthrough = "{\"upstreams\": [" + upstream + ", \"passthroughModels\": [\"m\"]}]";
        return Stream.of(
                Arguments.of("{}", "'upstreams' must be a non-empty array"),
                Arguments.of("not json", "not valid JSON"),
                Arguments.of("{\"upstreams\": [{\"name\": \"a\", \"baseUrl\": \"https://a/v1\"}]}", "'apiKey' is required"),
                Arguments.of("{\"upstreams\": [{\"name\": \"a\", \"baseUrl\": \"ftp://a\", \"apiKey\": \"k\"}]}",
                        "must start with http"),
                Arguments.of("{\"upstreams\": [{\"name\": \"a\", \"baseUrl\": \"https://a/v1\", \"apiKey\": \"${MISSING}\"}]}",
                        "'MISSING' is not set"),
                Arguments.of("{\"upstreams\": [" + upstream + ", \"discoverModels\": false}]}", "No model to serve"),
                Arguments.of("{\"upstreams\": [" + upstream + ", \"discoverModels\": \"maybe\"}]}", "true or false"),
                Arguments.of("{\"upstreams\": [" + upstream + "}], \"models\": {\"m\": [{\"upstream\": \"x\", \"model\": \"y\"}]}}",
                        "unknown upstream 'x'"),
                Arguments.of("{\"upstreams\": [" + upstream + "}, " + upstream + "}]}", "Duplicate upstream name"),
                Arguments.of(passthrough + ", \"defaultModel\": \"z\"}", "defaultModel 'z'"),
                Arguments.of(passthrough + ", \"retry\": {\"maxCooldown\": \"soon\"}}", "'maxCooldown'"),
                Arguments.of(passthrough + ", \"retry\": {\"maxAttempts\": 0}}", "maxAttempts must be at least 1"),
                Arguments.of("{\"upstreams\": [" + upstream + ", \"maxContextTokens\": 0}]}", "maxContextTokens must be at least 1"),
                Arguments.of("{\"upstreams\": [" + upstream + ", \"modelContextTokens\": {\"m\": \"big\"}}]}",
                        "must be a positive integer"),
                Arguments.of(passthrough + ", \"context\": {\"charsPerToken\": 0}}", "charsPerToken must be greater than 0"),
                Arguments.of(passthrough + ", \"context\": {\"truncationRatio\": 2}}", "truncationRatio"));
    }

    @ParameterizedTest
    @MethodSource("invalidConfigurations")
    void rejectsInvalidConfigurations(String json, String expectedMessage) {
        assertThatThrownBy(() -> loader.parse(json))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(expectedMessage);
    }
}
