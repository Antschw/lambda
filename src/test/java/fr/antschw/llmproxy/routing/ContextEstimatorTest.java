package fr.antschw.llmproxy.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ContextEstimatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void countsMessageTextAndFieldNames() throws IOException {
        String content = "x".repeat(3_500);
        JsonNode request = JSON.readTree("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"" + content + "\"}]}");

        // 3,500 content chars + "role" "user" "content" (15 chars) = 3,515 chars / 3.5
        assertThat(ContextEstimator.promptTokens(request, 3.5)).isEqualTo(1_004);
    }

    @Test
    void countsToolsMultiPartContentAndIgnoresOtherFields() throws IOException {
        JsonNode request = JSON.readTree("""
                {"model": "a-very-long-model-name-that-is-not-counted", "temperature": 0.7,
                 "messages": [{"role": "user", "content": [{"type": "text", "text": "hello"}]}],
                 "tools": [{"type": "function", "function": {"name": "read_file"}}]}""");

        long tokens = ContextEstimator.promptTokens(request, 1.0);

        assertThat(tokens).isEqualTo("roleusercontenttypetexttexthello".length()
                + "typefunctionfunctionnameread_file".length());
    }

    @Test
    void countsInlineImagesAsAFixedSize() throws IOException {
        JsonNode request = JSON.readTree("{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image/png;base64,"
                + "A".repeat(100_000) + "\"}}]}]}");

        assertThat(ContextEstimator.promptTokens(request, 4.0))
                .isBetween((long) ContextEstimator.INLINE_DATA_TOKENS, ContextEstimator.INLINE_DATA_TOKENS + 10L);
    }

    @Test
    void readsTheRequestedOutputTokens() throws IOException {
        assertThat(ContextEstimator.requestedOutputTokens(JSON.readTree("{\"max_tokens\":2048}"))).isEqualTo(2_048);
        assertThat(ContextEstimator.requestedOutputTokens(JSON.readTree("{\"max_completion_tokens\":512}"))).isEqualTo(512);
        assertThat(ContextEstimator.requestedOutputTokens(JSON.readTree("{\"max_output_tokens\":100}"))).isEqualTo(100);
        assertThat(ContextEstimator.requestedOutputTokens(JSON.readTree("{}"))).isZero();
        assertThat(ContextEstimator.promptTokens(null, 3.5)).isZero();
    }
}
