package fr.antschw.llmproxy.http;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Watches a response while it is copied to the client, without changing it, to read the token
 * usage reported by the upstream and the finish reason.
 *
 * <p>Handles JSON bodies ({@code usage.prompt_tokens}, or {@code usage.input_tokens} for the
 * Responses API) and server-sent events, where the usage comes in a final chunk when the client
 * asked for it ({@code stream_options.include_usage}, which most agentic tools do).
 */
final class UsageObserver {

    /** JSON bodies larger than this are not inspected. */
    private static final int MAX_JSON_BODY = 8 * 1024 * 1024;

    private final boolean sse;
    private final boolean json;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean overflow;
    private Long promptTokens;
    private Long completionTokens;
    private String finishReason;

    /**
     * @param contentType the response content type, possibly {@code null}
     */
    UsageObserver(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        this.sse = type.contains("text/event-stream");
        this.json = !sse && type.contains("json");
    }

    /** Feeds bytes as they are sent to the client. */
    void accept(byte[] bytes, int length) {
        if (sse) {
            for (int i = 0; i < length; i++) {
                byte b = bytes[i];
                if (b == '\n') {
                    line(buffer.toString(StandardCharsets.UTF_8));
                    buffer.reset();
                } else if (b != '\r') {
                    buffer.write(b);
                }
            }
        } else if (json && !overflow) {
            if (buffer.size() + length > MAX_JSON_BODY) {
                overflow = true;
                buffer.reset();
            } else {
                buffer.write(bytes, 0, length);
            }
        }
    }

    /** Call once the body is complete. */
    void finish() {
        if (sse && buffer.size() > 0) {
            line(buffer.toString(StandardCharsets.UTF_8));
        } else if (json && !overflow && buffer.size() > 0) {
            read(parse(buffer.toByteArray()));
        }
        buffer.reset();
    }

    Optional<Long> promptTokens() {
        return Optional.ofNullable(promptTokens);
    }

    Optional<Long> completionTokens() {
        return Optional.ofNullable(completionTokens);
    }

    Optional<String> finishReason() {
        return Optional.ofNullable(finishReason);
    }

    private void line(String line) {
        if (!line.startsWith("data:")) {
            return;
        }
        String data = line.substring(5).trim();
        if (data.isEmpty() || data.equals("[DONE]") || (!data.contains("usage") && !data.contains("finish_reason"))) {
            return;
        }
        read(parse(data.getBytes(StandardCharsets.UTF_8)));
    }

    private void read(JsonNode node) {
        if (node == null) {
            return;
        }
        // The Responses API wraps the final object: {"type": "response.completed", "response": {...}}.
        JsonNode root = node.has("response") && node.get("response").isObject() ? node.get("response") : node;
        JsonNode usage = root.get("usage");
        if (usage != null && usage.isObject()) {
            Long prompt = number(usage, "prompt_tokens", "input_tokens");
            Long completion = number(usage, "completion_tokens", "output_tokens");
            if (prompt != null) {
                promptTokens = prompt;
            }
            if (completion != null) {
                completionTokens = completion;
            }
        }
        for (JsonNode choice : root.path("choices")) {
            JsonNode reason = choice.get("finish_reason");
            if (reason != null && reason.isTextual()) {
                finishReason = reason.asText();
            }
        }
        JsonNode status = root.get("status");
        if (finishReason == null && root.has("output") && status != null && status.isTextual()) {
            finishReason = status.asText();
        }
    }

    private static Long number(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.canConvertToLong()) {
                return value.asLong();
            }
        }
        return null;
    }

    private static JsonNode parse(byte[] bytes) {
        try {
            return Responses.JSON.readTree(bytes);
        } catch (Exception e) {
            return null;
        }
    }
}
