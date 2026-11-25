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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Routing of models discovered from the upstreams' {@code GET /models}. */
class ModelDiscoveryRoutingTest {

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
    void spreadsEachModelOverTheUpstreamsExposingIt() throws Exception {
        a.models("m1", "m2", "m3");
        b.models("m1", "m2");
        c.models("m1");
        startProxy("", "");

        for (int i = 0; i < 6; i++) {
            assertThat(chat("m1").statusCode()).isEqualTo(200);
        }
        assertThat(List.of(a.received().size(), b.received().size(), c.received().size())).containsExactly(2, 2, 2);

        for (int i = 0; i < 4; i++) {
            chat("m2");
        }
        assertThat(List.of(a.received().size(), b.received().size(), c.received().size())).containsExactly(4, 4, 2);

        for (int i = 0; i < 3; i++) {
            chat("m3");
        }
        assertThat(List.of(a.received().size(), b.received().size(), c.received().size())).containsExactly(7, 4, 2);
        assertThat(a.received().getLast().body()).contains("\"model\":\"m3\"");
        assertThat(a.received().getLast().path()).isEqualTo("/v1/chat/completions");
    }

    @Test
    void listsTheUnionOfModelsWithTheirUpstreams() throws Exception {
        a.models("m2", "m1");
        b.models("m1");
        startProxy("", "");

        JsonNode data = json(get("/v1/models")).path("data");

        assertThat(data.findValuesAsText("id")).containsExactly("m1", "m2");
        assertThat(data.get(0).path("upstreams").toString()).isEqualTo("[\"a\",\"b\"]");
        assertThat(data.get(1).path("upstreams").toString()).isEqualTo("[\"a\"]");
        assertThat(data.get(0).path("object").asText()).isEqualTo("model");
    }

    @Test
    void collapsesDuplicateIdsSoAnUpstreamIsNotUsedTwiceAsOften() throws Exception {
        a.models("m1", "m1", "m1");
        b.models("m1");
        startProxy("", "");

        for (int i = 0; i < 4; i++) {
            chat("m1");
        }

        assertThat(json(get("/v1/models")).path("data").findValuesAsText("id")).containsExactly("m1");
        assertThat(a.received()).hasSize(2);
        assertThat(b.received()).hasSize(2);
    }

    @Test
    void ignoresExcludedModels() throws Exception {
        a.models("m1", "Text-Embedding-3", "whisper-1");
        startProxy(", \"excludeModels\": [\"*embed*\", \"whisper*\"]", "");

        assertThat(json(get("/v1/models")).path("data").findValuesAsText("id")).containsExactly("m1");
        assertThat(chat("Text-Embedding-3").statusCode()).isEqualTo(404);
    }

    @Test
    void keepsServingWhenAnUpstreamListIsUnavailableAndPicksItUpLater() throws Exception {
        a.models("m1");
        b.models("m1").modelsStatus(500);
        startProxy("", "\"discovery\": {\"refreshInterval\": \"200ms\"},");

        assertThat(upstreamsOf("m1")).containsExactly("a");
        assertThat(chat("m1").statusCode()).isEqualTo(200);

        b.modelsStatus(200);
        awaitUpstreams("m1", List.of("a", "b"));

        // A failing refresh keeps the last known list.
        b.modelsStatus(500);
        int seen = b.modelRequests();
        while (b.modelRequests() < seen + 2) {
            Thread.sleep(50);
        }
        assertThat(upstreamsOf("m1")).containsExactly("a", "b");
    }

    @Test
    void picksUpNewModelsOnRefresh() throws Exception {
        a.models("m1");
        startProxy("", "");
        assertThat(chat("m2").statusCode()).isEqualTo(404);

        a.models("m1", "m2");
        proxy.refreshModels();

        assertThat(chat("m2").statusCode()).isEqualTo(200);
    }

    @Test
    void doesNotDiscoverWhenDisabled() throws Exception {
        a.models("m1");
        startProxy(", \"discoverModels\": false, \"passthroughModels\": [\"static\"]", "");

        assertThat(a.modelRequests()).isZero();
        assertThat(json(get("/v1/models")).path("data").findValuesAsText("id")).containsExactly("static");
    }

    @Test
    void virtualModelsWinOverDiscoveredModelsWithTheSameName() throws Exception {
        a.models("coder", "m1");
        startProxy("", "\"models\": {\"coder\": [{\"upstream\": \"b\", \"model\": \"real-coder\"}]},");

        chat("coder");

        assertThat(a.received()).isEmpty();
        assertThat(b.received().getFirst().body()).contains("\"model\":\"real-coder\"");
        assertThat(json(get("/v1/models")).path("data").findValuesAsText("id")).containsExactly("coder", "m1");
    }

    @Test
    void servesClientTemplatesListingEveryModel() throws Exception {
        a.models("m1", "org/m2");
        startProxy("", "");
        String baseUrl = "http://127.0.0.1:" + proxy.port() + "/v1";

        JsonNode opencode = json(get("/templates/opencode.json"));
        JsonNode provider = opencode.path("providers").path("llm-proxy");
        assertThat(provider.path("package").asText()).isEqualTo("@opencode/ai/providers/openai-compatible");
        assertThat(provider.path("settings").path("baseURL").asText()).isEqualTo(baseUrl);
        assertThat(provider.path("settings").path("apiKey").asText()).isEqualTo("{env:LLM_PROXY_LOCAL_KEY}");
        assertThat(names(provider.path("models").fieldNames())).containsExactly("m1", "org/m2");

        JsonNode legacy = json(get("/templates/opencode.json?format=legacy")).path("provider").path("llm-proxy");
        assertThat(legacy.path("npm").asText()).isEqualTo("@ai-sdk/openai-compatible");
        assertThat(legacy.path("options").path("baseURL").asText()).isEqualTo(baseUrl);

        JsonNode pi = json(get("/templates/pi.json")).path("providers").path("llm-proxy");
        assertThat(pi.path("api").asText()).isEqualTo("openai-completions");
        assertThat(pi.path("baseUrl").asText()).isEqualTo(baseUrl);
        assertThat(pi.path("apiKey").asText()).isEqualTo("$LLM_PROXY_LOCAL_KEY");
        assertThat(pi.path("models").findValuesAsText("id")).containsExactly("m1", "org/m2");

        assertThat(get("/templates/unknown.json").statusCode()).isEqualTo(404);
    }

    // --- helpers -------------------------------------------------------------------------------

    private void startProxy(String upstreamAExtra, String extra) throws IOException {
        String json = """
                {
                  "upstreams": [
                    {"name": "a", "baseUrl": "%s", "apiKey": "key-a"%s},
                    {"name": "b", "baseUrl": "%s", "apiKey": "key-b"},
                    {"name": "c", "baseUrl": "%s", "apiKey": "key-c"}
                  ],
                  %s
                  "listen": {"host": "127.0.0.1", "port": 0},
                  "timeouts": {"request": "5s", "shutdown": "0s"}
                }""".formatted(a.baseUrl(), upstreamAExtra, b.baseUrl(), c.baseUrl(), extra);
        proxy = new ProxyServer(new ConfigLoader(name -> null).parse(json), Clock.systemUTC()).start();
    }

    private List<String> upstreamsOf(String model) {
        return proxy.pool().upstreamsOf(model);
    }

    private void awaitUpstreams(String model, List<String> expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!upstreamsOf(model).equals(expected) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(upstreamsOf(model)).isEqualTo(expected);
    }

    private HttpResponse<String> chat(String model) throws IOException, InterruptedException {
        String body = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        return client.send(HttpRequest.newBuilder(uri("/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
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

    private static List<String> names(java.util.Iterator<String> iterator) {
        List<String> list = new ArrayList<>();
        iterator.forEachRemaining(list::add);
        return list;
    }
}
