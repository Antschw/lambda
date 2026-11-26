package fr.antschw.llmproxy.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.antschw.llmproxy.config.ProxyConfig.Upstream;
import fr.antschw.llmproxy.routing.ContextEstimator;
import fr.antschw.llmproxy.routing.RetryAfter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Measures the context an upstream really handles, with a "needle in a haystack" test.
 *
 * <p>Each step sends one conversation of a given size directly to the upstream: a secret code in
 * the first user message, filler messages, then a question asking for the code. If the code comes
 * back, the whole conversation was seen; if not, the start was dropped (silent truncation) or the
 * request was rejected. Sizes double until a step fails, then a binary search narrows the limit.
 * Every step is a real, possibly long, request: it uses the upstream's quota.
 */
public final class ContextProbe {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int FILLER_MESSAGE_CHARS = 8_000;
    private static final int MAX_RETRIES = 5;
    private static final String[] WORDS = {
            "river", "mill", "village", "lantern", "harbour", "orchard", "bridge", "market", "valley", "tower",
            "garden", "meadow", "forest", "station", "library", "workshop", "canal", "festival", "quarry", "island"
    };

    /** Result of one step. */
    public enum Outcome {
        /** The code came back: the whole conversation was seen. */
        REMEMBERED,
        /** The upstream answered without the code: the start of the conversation was probably dropped. */
        FORGOT,
        /** The upstream refused the request as too long. */
        REJECTED
    }

    /**
     * @param estimatedTokens size sent, as estimated by the proxy
     * @param promptTokens    size counted by the upstream, or {@code null} if not reported
     * @param outcome         what happened
     */
    public record Step(long estimatedTokens, Long promptTokens, Outcome outcome) {
    }

    /**
     * @param steps          every step, in order
     * @param largestWorking largest estimated size remembered, or 0 if none worked
     * @param smallestFailed smallest estimated size that failed, or 0 if none failed
     */
    public record Result(List<Step> steps, long largestWorking, long smallestFailed) {

        /** @return a {@code maxContextTokens} value with a 10% margin, rounded down to 1,000 */
        public long suggestedLimit() {
            return Math.max(1_000, (long) (largestWorking * 0.9) / 1_000 * 1_000);
        }
    }

    private final Upstream upstream;
    private final String model;
    private final HttpClient client;
    private final double charsPerToken;
    private final Duration timeout;
    private final Consumer<String> out;
    private final Random random = new Random();

    /**
     * @param upstream      the upstream to probe, called directly (not through the proxy)
     * @param model         model id on that upstream
     * @param client        HTTP client
     * @param charsPerToken ratio used to size requests, as in the proxy configuration
     * @param timeout       time allowed for one step
     * @param out           receives one human-readable line per step
     */
    public ContextProbe(Upstream upstream, String model, HttpClient client, double charsPerToken, Duration timeout,
                        Consumer<String> out) {
        this.upstream = upstream;
        this.model = model;
        this.client = client;
        this.charsPerToken = charsPerToken;
        this.timeout = timeout;
        this.out = out;
    }

    /**
     * Runs the search.
     *
     * @param minTokens first size tried
     * @param maxTokens largest size tried
     * @return every step and the bounds found
     * @throws IOException          if the upstream cannot be reached or answers an unexpected error
     * @throws InterruptedException if interrupted
     */
    public Result run(long minTokens, long maxTokens) throws IOException, InterruptedException {
        List<Step> steps = new ArrayList<>();
        long working = 0;
        long failed = 0;
        long size = minTokens;
        while (true) {
            Step step = step(size);
            steps.add(step);
            if (step.outcome() != Outcome.REMEMBERED) {
                failed = step.estimatedTokens();
                break;
            }
            working = step.estimatedTokens();
            if (size >= maxTokens) {
                break;
            }
            size = Math.min(size * 2, maxTokens);
        }
        if (working > 0 && failed > 0) {
            while (failed - working > Math.max(1_000, working / 20)) {
                Step step = step((working + failed) / 2);
                steps.add(step);
                if (step.outcome() == Outcome.REMEMBERED) {
                    working = step.estimatedTokens();
                } else {
                    failed = step.estimatedTokens();
                }
            }
        }
        return new Result(steps, working, failed);
    }

    private Step step(long targetTokens) throws IOException, InterruptedException {
        String code = "ORCHID-" + (1_000 + random.nextInt(9_000));
        ObjectNode request = request(code, targetTokens);
        long estimated = ContextEstimator.promptTokens(request, charsPerToken);
        HttpResponse<byte[]> response = send(request);
        String body = new String(response.body(), StandardCharsets.UTF_8);

        Step step;
        if (response.statusCode() == 200) {
            JsonNode json = JSON.readTree(response.body());
            String answer = json.path("choices").path(0).path("message").path("content").asText("");
            JsonNode prompt = json.path("usage").path("prompt_tokens");
            step = new Step(estimated, prompt.canConvertToLong() ? prompt.asLong() : null,
                    answer.toUpperCase(Locale.ROOT).contains(code) ? Outcome.REMEMBERED : Outcome.FORGOT);
        } else if (response.statusCode() == 400 || response.statusCode() == 413) {
            step = new Step(estimated, null, Outcome.REJECTED);
        } else {
            throw new IOException("Upstream '" + upstream.name() + "' answered " + response.statusCode() + ": "
                    + body.substring(0, Math.min(300, body.length())));
        }
        out.accept(String.format(Locale.ROOT, "  ~%,7d tokens sent  %-22s  %s", estimated,
                step.promptTokens() == null ? "" : "upstream counted " + String.format(Locale.ROOT, "%,d", step.promptTokens()),
                switch (step.outcome()) {
                    case REMEMBERED -> "OK (code remembered)";
                    case FORGOT -> "FORGOTTEN (start of the conversation was dropped)";
                    case REJECTED -> "REJECTED (" + body.substring(0, Math.min(120, body.length())).replace('\n', ' ') + ")";
                }));
        return step;
    }

    private ObjectNode request(String code, long targetTokens) {
        ObjectNode request = JSON.createObjectNode().put("model", model).put("max_tokens", 32).put("temperature", 0);
        ArrayNode messages = request.putArray("messages");
        messages.addObject().put("role", "system").put("content", "You are a helpful assistant with a perfect memory.");
        messages.addObject().put("role", "user")
                .put("content", "The secret code is " + code + ". Remember it: I will ask for it at the end.");
        messages.addObject().put("role", "assistant").put("content", "Understood, I will remember the secret code.");

        long fillerChars = Math.max(0, Math.round(targetTokens * charsPerToken) - 400);
        Random filler = new Random(42);
        int paragraph = 0;
        while (fillerChars > 0) {
            StringBuilder text = new StringBuilder("Here is some background reading, no answer needed.\n");
            while (text.length() < Math.min(FILLER_MESSAGE_CHARS, fillerChars)) {
                text.append("Note ").append(++paragraph).append(": the ").append(WORDS[filler.nextInt(WORDS.length)])
                        .append(" near the ").append(WORDS[filler.nextInt(WORDS.length)]).append(" was visited ")
                        .append(filler.nextInt(100)).append(" times by the people of the ")
                        .append(WORDS[filler.nextInt(WORDS.length)]).append(". ");
            }
            fillerChars -= text.length();
            messages.addObject().put("role", "user").put("content", text.toString());
            messages.addObject().put("role", "assistant").put("content", "Noted.");
        }
        messages.addObject().put("role", "user")
                .put("content", "What is the secret code I gave you in my first message? Reply with the code only.");
        return request;
    }

    private HttpResponse<byte[]> send(ObjectNode request) throws IOException, InterruptedException {
        String base = upstream.baseUrl().toString();
        URI uri = URI.create((base.endsWith("/") ? base : base + "/") + "chat/completions");
        byte[] body = JSON.writeValueAsBytes(request);
        for (int attempt = 1; ; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Content-Type", "application/json");
            upstream.headers().forEach(builder::setHeader);
            builder.setHeader("Authorization", "Bearer " + upstream.apiKey());
            HttpResponse<byte[]> response = client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if ((status != 429 && status != 503) || attempt == MAX_RETRIES) {
                return response;
            }
            Duration wait = RetryAfter.fromHeaders(response.headers(), Instant.now()).orElse(Duration.ofSeconds(10));
            wait = wait.compareTo(Duration.ofSeconds(60)) > 0 ? Duration.ofSeconds(60) : wait;
            out.accept("  (" + status + " from the upstream, waiting " + wait.toSeconds() + "s)");
            Thread.sleep(wait);
        }
    }
}
