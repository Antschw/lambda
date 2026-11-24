package fr.antschw.llmproxy.http;

import com.fasterxml.jackson.databind.JsonNode;
import fr.antschw.llmproxy.config.ConfigLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyServerTest {

    private static final String LOCAL_KEY = "local-secret";
    private static final String CHAT = "{\"model\":\"coder\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    private final HttpClient client = HttpClient.newHttpClient();
    private FakeUpstream a;
    private FakeUpstream b;
    private FakeUpstream c;
    private ProxyServer proxy;

    @BeforeEach
    void startUpstreams() throws IOException {
        a = new FakeUpstream("a");
        b = new FakeUpstream("b");
        c = new FakeUpstream("c");
    }

    @AfterEach
    void stopAll() {
        if (proxy != null) {
            proxy.close();
        }
        a.close();
        b.close();
        c.close();
        client.close();
    }

    @Test
    void spreadsRequestsInRoundRobinAndRewritesModelAndKey() throws Exception {
        startProxy(threeUpstreams(), "");

        for (int i = 0; i < 6; i++) {
            HttpResponse<String> response = post("/v1/chat/completions", CHAT);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-LLM-Proxy-Target")).isPresent();
        }

        assertThat(a.received()).hasSize(2);
        assertThat(b.received()).hasSize(2);
        assertThat(c.received()).hasSize(2);
        FakeUpstream.Received received = a.received().getFirst();
        assertThat(received.path()).isEqualTo("/v1/chat/completions");
        assertThat(received.body()).contains("\"model\":\"model-a\"").contains("\"content\":\"hi\"");
        assertThat(received.header("Authorization")).isEqualTo("Bearer key-a");
        assertThat(b.received().getFirst().header("Authorization")).isEqualTo("Bearer key-b");
        assertThat(c.received().getFirst().body()).contains("\"model\":\"model-c\"");
    }

    @Test
    void failsOverOn429AndSkipsTheTargetDuringItsCooldown() throws Exception {
        a.behave(FakeUpstream.status(429, Map.of("Retry-After", "1")));
        startProxy(threeUpstreams(), "");

        HttpResponse<String> first = post("/v1/chat/completions", CHAT);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(json(first).path("upstream").asText()).isEqualTo("b");

        for (int i = 0; i < 4; i++) {
            assertThat(post("/v1/chat/completions", CHAT).statusCode()).isEqualTo(200);
        }
        assertThat(a.received()).hasSize(1);
        assertThat(b.received().size() + c.received().size()).isEqualTo(5);

        a.behave(a.ok());
        Thread.sleep(1_100);
        for (int i = 0; i < 3; i++) {
            post("/v1/chat/completions", CHAT);
        }
        assertThat(a.received()).hasSizeGreaterThan(1);
    }

    @Test
    void waitsForACoolingTargetWhenAllAreRateLimited() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        a.behave((exchange, request) -> {
            if (calls.incrementAndGet() == 1) {
                FakeUpstream.status(429, Map.of("Retry-After", "1")).respond(exchange, request);
            } else {
                a.ok().respond(exchange, request);
            }
        });
        startProxy(singleUpstream(), "\"retry\": {\"maxWaitWhenAllCooling\": \"3s\"},");

        long start = System.nanoTime();
        HttpResponse<String> response = post("/v1/chat/completions", CHAT);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
        assertThat(a.received()).hasSize(2);
    }

    @Test
    void answers429WithRetryAfterWhenWaitingWouldTakeTooLong() throws Exception {
        a.behave(FakeUpstream.status(429, Map.of("Retry-After", "30")));
        startProxy(singleUpstream(), "\"retry\": {\"maxWaitWhenAllCooling\": \"1s\", \"maxAttempts\": 3},");

        HttpResponse<String> response = post("/v1/chat/completions", CHAT);

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).hasValueSatisfying(
                v -> assertThat(Integer.parseInt(v)).isBetween(28, 30));
        assertThat(a.received()).hasSize(1);
    }

    @Test
    void doesNotRetryClientErrors() throws Exception {
        a.behave(FakeUpstream.status(400, Map.of()));
        startProxy(threeUpstreams(), "");

        HttpResponse<String> response = post("/v1/chat/completions", CHAT);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("status 400");
        assertThat(b.received()).isEmpty();
        assertThat(c.received()).isEmpty();
    }

    @Test
    void setsAsideATargetWithABadKey() throws Exception {
        a.behave(FakeUpstream.status(401, Map.of()));
        startProxy(threeUpstreams(), "");

        assertThat(post("/v1/chat/completions", CHAT).statusCode()).isEqualTo(200);
        for (int i = 0; i < 4; i++) {
            post("/v1/chat/completions", CHAT);
        }

        assertThat(a.received()).hasSize(1);
        JsonNode health = json(get("/health", false));
        JsonNode targetA = health.path("models").path("coder").get(0);
        assertThat(targetA.path("misconfigured").asBoolean()).isTrue();
        assertThat(targetA.path("available").asBoolean()).isFalse();
        assertThat(health.path("status").asText()).isEqualTo("ok");
    }

    @Test
    void triesAnotherTargetOn404WithoutCoolingDown() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        a.behave((exchange, request) -> {
            if (calls.incrementAndGet() == 1) {
                FakeUpstream.status(404, Map.of()).respond(exchange, request);
            } else {
                a.ok().respond(exchange, request);
            }
        });
        startProxy(threeUpstreams(), "");

        HttpResponse<String> response = post("/v1/chat/completions", CHAT);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("upstream").asText()).isEqualTo("b");

        JsonNode targetA = json(get("/health", false)).path("models").path("coder").get(0);
        assertThat(targetA.path("available").asBoolean()).isTrue();
    }

    @Test
    void returnsTheLastUpstreamErrorWhenEveryAttemptFails() throws Exception {
        for (FakeUpstream upstream : List.of(a, b, c)) {
            upstream.behave(FakeUpstream.status(503, Map.of("Retry-After", "0.05")));
        }
        startProxy(threeUpstreams(), "\"retry\": {\"maxAttempts\": 4, \"maxWaitWhenAllCooling\": \"2s\"},");

        HttpResponse<String> response = post("/v1/chat/completions", CHAT);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("status 503");
        assertThat(a.received().size() + b.received().size() + c.received().size()).isEqualTo(4);
    }

    @Test
    void failsOverOnNetworkErrors() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        startProxy("""
                "upstreams": [
                  {"name": "dead", "baseUrl": "http://127.0.0.1:%d/v1", "apiKey": "key-dead"},
                  {"name": "b", "baseUrl": "%s", "apiKey": "key-b"}
                ],
                "models": {"coder": [{"upstream": "dead", "model": "m"}, {"upstream": "b", "model": "model-b"}]},
                """.formatted(closedPort, b.baseUrl()), "");

        HttpResponse<String> response = post("/v1/chat/completions", CHAT);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("upstream").asText()).isEqualTo("b");
    }

    @Test
    void streamsServerSentEventsAsTheyArrive() throws Exception {
        CountDownLatch firstChunkRead = new CountDownLatch(1);
        a.behave((exchange, request) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("data: {\"chunk\":1}\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                firstChunkRead.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            out.write("data: {\"chunk\":2}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.close();
        });
        startProxy(singleUpstream(), "");

        HttpResponse<InputStream> response = client.send(request("/v1/chat/completions")
                .POST(HttpRequest.BodyPublishers.ofString(CHAT.replace("}]}", "}],\"stream\":true}"))).build(),
                HttpResponse.BodyHandlers.ofInputStream());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("text/event-stream");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            // Read before the upstream sends the rest: proves the proxy does not buffer the stream.
            assertThat(reader.readLine()).isEqualTo("data: {\"chunk\":1}");
            firstChunkRead.countDown();
            assertThat(reader.lines().toList()).contains("data: {\"chunk\":2}", "data: [DONE]");
        }
        assertThat(a.received().getFirst().body()).contains("\"stream\":true");
    }

    @Test
    void listsModelsWithOrWithoutTheV1Prefix() throws Exception {
        startProxy("""
                "upstreams": [{"name": "a", "baseUrl": "%s", "apiKey": "key-a", "passthroughModels": ["raw-model"]}],
                "models": {"coder": [{"upstream": "a", "model": "model-a"}]},
                """.formatted(a.baseUrl()), "");

        for (String path : List.of("/v1/models", "/models", "/v1/models/")) {
            JsonNode body = json(get(path, true));
            assertThat(body.path("object").asText()).isEqualTo("list");
            assertThat(body.path("data").findValuesAsText("id")).containsExactly("coder", "raw-model");
        }
        assertThat(json(get("/v1/models/coder", true)).path("id").asText()).isEqualTo("coder");
        assertThat(get("/v1/models/unknown", true).statusCode()).isEqualTo(404);
        assertThat(a.received()).isEmpty();
    }

    @Test
    void forwardsPassthroughModelsUnchanged() throws Exception {
        startProxy("""
                "upstreams": [{"name": "a", "baseUrl": "%s", "apiKey": "key-a", "passthroughModels": ["raw-model"]}],
                "models": {"coder": [{"upstream": "a", "model": "model-a"}]},
                """.formatted(a.baseUrl()), "");

        HttpResponse<String> response = post("/v1/chat/completions", CHAT.replace("coder", "raw-model"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(a.received().getFirst().body()).contains("\"model\":\"raw-model\"");
    }

    @Test
    void forwardsPathsWithoutV1PrefixQueryStringAndExtraHeaders() throws Exception {
        startProxy("""
                "upstreams": [{"name": "a", "baseUrl": "%s/", "apiKey": "key-a", "headers": {"X-Org": "org-1"}}],
                "models": {"coder": [{"upstream": "a", "model": "model-a"}]},
                """.formatted(a.baseUrl()), "");

        HttpResponse<String> response = client.send(request("/chat/completions?api-version=1")
                .header("X-Custom", "kept")
                .POST(HttpRequest.BodyPublishers.ofString(CHAT)).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("x-request-id")).hasValue("req-a");
        FakeUpstream.Received received = a.received().getFirst();
        assertThat(received.path()).isEqualTo("/v1/chat/completions");
        assertThat(received.query()).isEqualTo("api-version=1");
        assertThat(received.header("X-Org")).isEqualTo("org-1");
        assertThat(received.header("X-Custom")).isEqualTo("kept");
        assertThat(received.header("Authorization")).isEqualTo("Bearer key-a");
    }

    @Test
    void rejectsUnknownModelsUnlessADefaultModelIsSet() throws Exception {
        startProxy(singleUpstream(), "");
        HttpResponse<String> unknown = post("/v1/chat/completions", CHAT.replace("coder", "gpt-4o"));
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json(unknown).path("error").path("code").asText()).isEqualTo("model_not_found");
        proxy.close();

        startProxy(singleUpstream(), "\"defaultModel\": \"coder\",");
        HttpResponse<String> routed = post("/v1/chat/completions", CHAT.replace("coder", "gpt-4o"));
        assertThat(routed.statusCode()).isEqualTo(200);
        assertThat(a.received().getFirst().body()).contains("\"model\":\"model-a\"");
    }

    @Test
    void requiresTheLocalKeyAsBearerOrXApiKey() throws Exception {
        startProxy(singleUpstream(), "\"localApiKeys\": [\"" + LOCAL_KEY + "\"],");

        HttpResponse<String> missing = client.send(HttpRequest.newBuilder(uri("/v1/models")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(json(missing).path("error").path("code").asText()).isEqualTo("invalid_api_key");

        HttpResponse<String> wrong = client.send(HttpRequest.newBuilder(uri("/v1/models"))
                .header("Authorization", "Bearer nope").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(wrong.statusCode()).isEqualTo(401);

        HttpResponse<String> apiKey = client.send(HttpRequest.newBuilder(uri("/v1/models"))
                .header("x-api-key", LOCAL_KEY).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(apiKey.statusCode()).isEqualTo(200);

        assertThat(post("/v1/chat/completions", CHAT).statusCode()).isEqualTo(200);
        assertThat(a.received().getFirst().header("Authorization")).isEqualTo("Bearer key-a");
        assertThat(get("/health", false).statusCode()).isEqualTo(200);
    }

    // --- helpers -------------------------------------------------------------------------------

    private String threeUpstreams() {
        return """
                "upstreams": [
                  {"name": "a", "baseUrl": "%s", "apiKey": "key-a"},
                  {"name": "b", "baseUrl": "%s", "apiKey": "key-b"},
                  {"name": "c", "baseUrl": "%s", "apiKey": "key-c"}
                ],
                "models": {"coder": [
                  {"upstream": "a", "model": "model-a"},
                  {"upstream": "b", "model": "model-b"},
                  {"upstream": "c", "model": "model-c"}
                ]},
                """.formatted(a.baseUrl(), b.baseUrl(), c.baseUrl());
    }

    private String singleUpstream() {
        return """
                "upstreams": [{"name": "a", "baseUrl": "%s", "apiKey": "key-a"}],
                "models": {"coder": [{"upstream": "a", "model": "model-a"}]},
                """.formatted(a.baseUrl());
    }

    private void startProxy(String upstreamsAndModels, String extra) throws IOException {
        String json = "{" + upstreamsAndModels + extra + """
                "listen": {"host": "127.0.0.1", "port": 0},
                "timeouts": {"request": "5s", "shutdown": "0s"}
                }""";
        proxy = new ProxyServer(new ConfigLoader(name -> null).parse(json), Clock.systemUTC()).start();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + proxy.port() + path);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + LOCAL_KEY)
                .header("Content-Type", "application/json");
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        return client.send(request(path).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, boolean authenticated) throws IOException, InterruptedException {
        HttpRequest.Builder builder = authenticated ? request(path) : HttpRequest.newBuilder(uri(path));
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws IOException {
        return Responses.JSON.readTree(response.body());
    }
}
