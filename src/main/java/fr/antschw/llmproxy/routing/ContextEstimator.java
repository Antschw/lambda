package fr.antschw.llmproxy.routing;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Estimates how many tokens a request needs, without a tokenizer.
 *
 * <p>The estimate counts the characters of every text value of the prompt fields
 * ({@code messages}, {@code tools}, {@code input}, {@code prompt}, {@code system},
 * {@code instructions}, {@code functions}) and divides by a characters-per-token ratio. Images or
 * files sent inline as {@code data:} URLs count as a fixed number of tokens instead of their
 * base64 length. Field names are counted too, as a rough stand-in for the chat template overhead.
 * It is an approximation: tokenizers differ between models, so limits should keep a margin.
 */
public final class ContextEstimator {

    /** Tokens counted for one inline image or file. */
    static final int INLINE_DATA_TOKENS = 1_000;

    private static final List<String> PROMPT_FIELDS =
            List.of("messages", "tools", "functions", "input", "prompt", "system", "instructions");
    private static final List<String> OUTPUT_FIELDS =
            List.of("max_completion_tokens", "max_tokens", "max_output_tokens");

    private ContextEstimator() {
    }

    /**
     * @param request       the request body
     * @param charsPerToken characters per token
     * @return estimated prompt tokens
     */
    public static long promptTokens(JsonNode request, double charsPerToken) {
        if (request == null) {
            return 0;
        }
        long[] chars = {0};
        long[] fixedTokens = {0};
        for (String field : PROMPT_FIELDS) {
            JsonNode value = request.get(field);
            if (value != null) {
                walk(value, chars, fixedTokens);
            }
        }
        return Math.round(chars[0] / charsPerToken) + fixedTokens[0];
    }

    /**
     * @param request the request body
     * @return output tokens reserved by the request ({@code max_tokens} and equivalents), or 0
     */
    public static long requestedOutputTokens(JsonNode request) {
        if (request == null) {
            return 0;
        }
        for (String field : OUTPUT_FIELDS) {
            JsonNode value = request.get(field);
            if (value != null && value.canConvertToLong() && value.asLong() > 0) {
                return value.asLong();
            }
        }
        return 0;
    }

    private static void walk(JsonNode node, long[] chars, long[] fixedTokens) {
        if (node.isTextual()) {
            String text = node.asText();
            if (text.startsWith("data:")) {
                fixedTokens[0] += INLINE_DATA_TOKENS;
            } else {
                chars[0] += text.length();
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                chars[0] += entry.getKey().length();
                walk(entry.getValue(), chars, fixedTokens);
            }
        } else if (node.isArray()) {
            node.forEach(item -> walk(item, chars, fixedTokens));
        } else if (node.isNumber() || node.isBoolean()) {
            chars[0] += node.asText().length();
        }
    }
}
