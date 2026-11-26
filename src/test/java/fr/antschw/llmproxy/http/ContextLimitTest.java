package fr.antschw.llmproxy.http;

import com.fasterxml.jackson.databind.JsonNode;
import fr.antschw.llmproxy.config.ConfigLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Context limits, silent truncation detection and context-overflow failover. */
class ContextLimitTest {

    /** About 5,700 estimated tokens with the default 3.5 characters per token. */
    private static final String LONG = "word ".repeat(4_000);

    private final HttpClient client = HttpClient.newHttpClient();
    private FakeUpstream a;
    private FakeUpstream b;
    private ProxyServer proxy;

    @BeforeEach
    void startUpstreams() throws IOException {
        a = new FakeUpstream("a").models("m1");
        b = new FakeUpstream("b").models("m1");
    }

    @AfterEach
    void stopAll() {
        if (proxy != null) {
            proxy.close();
        }
        a.close();
        b.close();
        client.close();
    }

    @Test
    void sendsLongRequestsOnlyToUpstreamsWithALargeEnoughContext() throws Exception {
        startProxy(", \"maxContextTokens\": 1000", "", "");

        for (int i = 0; i < 4; i++) {
            assertThat(chat("short").statusCode()).isEqualTo(200);
        }
        assertThat(a.received()).hasSize(2);

        for (int i = 0; i < 3; i++) {
            assertThat(chat(LONG).statusCode()).isEqualTo(200);
        }
        assertThat(a.received()).hasSize(2);
        assertThat(b.received()).hasSize(5);
    }

    @Test
    void countsTheRequestedOutputTokensInTheNeededContext() throws Exception {
        startProxy(", \"maxContextTokens\": 2000", "", "");

        for (int i = 0; i < 3; i++) {
            chat("short", 1_999);
        }

        assertThat(a.received()).isEmpty();
    }

    @Test
    void usesPerModelLimitsBeforeTheUpstreamDefault() throws Exception {
        startProxy(", \"maxContextTokens\": 1000, \"modelContextTokens\": {\"m*\": 100000}", "", "");

        for (int i = 0; i < 4; i++) {
            chat(LONG);
        }

        assertThat(a.received()).hasSize(2);
    }

    @Test
    void fallsBackToTheLargestContextWhenNoUpstreamIsLargeEnough() throws Exception {
        startProxy(", \"maxContextTokens\": 1000", ", \"maxContextTokens\": 2000", "");

        for (int i = 0; i < 3; i++) {
            assertThat(chat(LONG).statusCode()).isEqualTo(200);
        }

        assertThat(a.received()).isEmpty();
        assertThat(b.received()).hasSize(3);
    }

    @Test
    void triesAnotherUpstreamOnAContextLengthError() throws Exception {
        a.behave(FakeUpstream.status(400, "{\"error\":{\"message\":\"This model's maximum context "
                + "length is 32768 tokens\",\"code\":\"context_length_exceeded\"}}"));
        startProxy("", "", "");

        HttpResponse<String> response = chat(LONG);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("upstream").asText()).isEqualTo("b");
        assertThat(target(0).path("available").asBoolean()).isTrue();
    }

    @Test
    void returnsTheContextErrorWhenEveryUpstreamRejectsTheRequest() throws Exception {
        FakeUpstream.Behavior tooLong = FakeUpstream.status(400,
                "{\"error\":{\"message\":\"prompt is too long\"}}");
        a.behave(tooLong);
        b.behave(tooLong);
        startProxy("", "", "");

        HttpResponse<String> response = chat(LONG);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("prompt is too long");
        assertThat(a.received().size() + b.received().size()).isEqualTo(2);
    }

    @Test
    void reportsPossibleSilentTruncationWithoutChangingTheResponse() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"content\":\"Task completed\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":800,\"completion_tokens\":3}}";
        a.behave((exchange, request) -> FakeUpstream.send(exchange, 200, body, Map.of()));
        b.behave((exchange, request) -> FakeUpstream.send(exchange, 200, body.replace("800", "5600"), Map.of()));
        startProxy("", "", "");

        HttpResponse<String> first = chat(LONG);
        chat(LONG);

        assertThat(first.body()).isEqualTo(body);
        JsonNode targetA = target(0);
        assertThat(targetA.path("truncationsSuspected").asLong()).isEqualTo(1);
        assertThat(targetA.path("maxPromptTokensSeen").asLong()).isEqualTo(800);
        assertThat(target(1).path("truncationsSuspected").asLong()).isZero();
    }

    @Test
    void readsUsageFromStreamsToo() throws Exception {
        a.behave((exchange, request) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"Task completed\"}}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":700,\"completion_tokens\":3}}\n\n"
                        + "data: [DONE]\n\n").getBytes());
            }
        });
        startProxy("", "", "");

        chat(LONG);

        assertThat(target(0).path("truncationsSuspected").asLong()).isEqualTo(1);
    }

    @Test
    void learnsTheLimitFromATruncationWhenEnabled() throws Exception {
        a.behave((exchange, request) -> FakeUpstream.send(exchange, 200,
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":800}}", Map.of()));
        startProxy("", "", "\"context\": {\"learnFromTruncation\": true},");

        chat(LONG);
        for (int i = 0; i < 4; i++) {
            chat(LONG);
        }

        assertThat(a.received()).hasSize(1);
        assertThat(target(0).path("contextLimit").asLong()).isEqualTo(800);
        assertThat(chat("short").statusCode()).isEqualTo(200);
    }

    @Test
    void usesTheContextSizeAdvertisedByModelsAndShowsIt() throws Exception {
        a.contextLengths(Map.of("m1", 1000));
        b.contextLengths(Map.of("m1", 128_000));
        startProxy("", "", "");

        for (int i = 0; i < 3; i++) {
            chat(LONG);
        }

        assertThat(a.received()).isEmpty();
        assertThat(json(get("/v1/models")).path("data").get(0).path("context_length").asLong()).isEqualTo(128_000);
        assertThat(json(get("/templates/pi.json")).path("providers").path("llm-proxy").path("models").get(0)
                .path("contextWindow").asLong()).isEqualTo(128_000);
        assertThat(json(get("/templates/opencode.json")).path("providers").path("llm-proxy").path("models")
                .path("m1").path("limit").path("context").asLong()).isEqualTo(128_000);
    }

    @Test
    void configuredLimitsWinOverAdvertisedOnes() throws Exception {
        a.contextLengths(Map.of("m1", 128_000));
        startProxy(", \"maxContextTokens\": 1000", "", "");

        assertThat(target(0).path("contextLimit").asLong()).isEqualTo(1_000);
        assertThat(target(1).has("contextLimit")).isFalse();
        assertThat(json(get("/v1/models")).path("data").get(0).has("context_length")).isFalse();
    }

    // --- helpers -------------------------------------------------------------------------------

    private void startProxy(String aExtra, String bExtra, String extra) throws IOException {
        String json = """
                {
                  "upstreams": [
                    {"name": "a", "baseUrl": "%s", "apiKey": "key-a"%s},
                    {"name": "b", "baseUrl": "%s", "apiKey": "key-b"%s}
                  ],
                  %s
                  "listen": {"host": "127.0.0.1", "port": 0},
                  "timeouts": {"request": "5s", "shutdown": "0s"}
                }""".formatted(a.baseUrl(), aExtra, b.baseUrl(), bExtra, extra);
        proxy = new ProxyServer(new ConfigLoader(name -> null).parse(json), Clock.systemUTC()).start();
    }

    private HttpResponse<String> chat(String content) throws IOException, InterruptedException {
        return chat(content, 0);
    }

    private HttpResponse<String> chat(String content, int maxTokens) throws IOException, InterruptedException {
        String body = "{\"model\":\"m1\"," + (maxTokens > 0 ? "\"max_tokens\":" + maxTokens + "," : "")
                + "\"messages\":[{\"role\":\"user\",\"content\":\"" + content + "\"}]}";
        return client.send(HttpRequest.newBuilder(uri("/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode target(int index) throws IOException, InterruptedException {
        return json(get("/health")).path("models").path("m1").get(index);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + proxy.port() + path);
    }

    private static JsonNode json(HttpResponse<String> response) throws IOException {
        return Responses.JSON.readTree(response.body());
    }
}
