package fr.antschw.llmproxy.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fr.antschw.llmproxy.config.ProxyConfig.Upstream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContextProbeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> output = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        server.stop(0);
        client.close();
    }

    @Test
    void findsTheContextOfAnUpstreamThatSilentlyDropsTheStartOfConversations() throws Exception {
        // Keeps the system message and drops the oldest messages beyond 70,000 characters (~20k tokens).
        start(70_000, false);

        ContextProbe.Result result = probe().run(4_000, 131_072);

        assertThat(result.largestWorking()).isBetween(17_000L, 20_000L);
        assertThat(result.smallestFailed()).isBetween(20_000L, 22_000L);
        assertThat(result.suggestedLimit()).isBetween(15_000L, 18_000L).matches(v -> v % 1_000 == 0);
        assertThat(result.steps()).anyMatch(s -> s.outcome() == ContextProbe.Outcome.FORGOT);
        assertThat(result.steps()).allMatch(s -> s.promptTokens() != null);
        assertThat(output).isNotEmpty();
    }

    @Test
    void recognisesUpstreamsThatRejectLongRequests() throws Exception {
        start(70_000, true);

        ContextProbe.Result result = probe().run(4_000, 131_072);

        assertThat(result.largestWorking()).isBetween(17_000L, 20_000L);
        assertThat(result.steps()).anyMatch(s -> s.outcome() == ContextProbe.Outcome.REJECTED);
    }

    @Test
    void reportsNoLimitBelowTheMaximum() throws Exception {
        start(Integer.MAX_VALUE, false);

        ContextProbe.Result result = probe().run(1_000, 8_000);

        assertThat(result.largestWorking()).isGreaterThan(7_000L);
        assertThat(result.smallestFailed()).isZero();
        assertThat(result.steps()).hasSize(4);
    }

    private ContextProbe probe() {
        Upstream upstream = new Upstream("small", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "key", Map.of(), List.of(), Optional.empty(), false, List.of(), List.of(), Optional.empty(), Map.of());
        return new ContextProbe(upstream, "mistral-small", client, 3.5, Duration.ofSeconds(10), output::add);
    }

    /** A fake model that answers the secret code if it is still in the (possibly truncated) conversation. */
    private void start(int maxChars, boolean reject) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                List<String> contents = new ArrayList<>();
                request.path("messages").forEach(m -> contents.add(m.path("content").asText()));
                int total = contents.stream().mapToInt(String::length).sum();
                String body;
                int status = 200;
                if (reject && total > maxChars) {
                    status = 400;
                    body = "{\"error\":{\"message\":\"maximum context length exceeded\"}}";
                } else {
                    // Like Ollama: keep the system message, drop the oldest other messages.
                    while (total > maxChars && contents.size() > 2) {
                        total -= contents.remove(1).length();
                    }
                    String all = String.join("\n", contents);
                    java.util.regex.Matcher code = java.util.regex.Pattern.compile("ORCHID-\\d{4}").matcher(all);
                    String answer = code.find() ? code.group() : "I do not know.";
                    body = "{\"choices\":[{\"message\":{\"content\":\"" + answer + "\"}}],\"usage\":{\"prompt_tokens\":"
                            + (total / 4) + "}}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
        server.start();
    }
}
