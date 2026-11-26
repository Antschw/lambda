package fr.antschw.llmproxy.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class UsageObserverTest {

    @Test
    void readsUsageFromAJsonBody() {
        UsageObserver observer = feed("application/json; charset=utf-8",
                "{\"choices\":[{\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":34}}");

        assertThat(observer.promptTokens()).hasValue(1_200L);
        assertThat(observer.completionTokens()).hasValue(34L);
        assertThat(observer.finishReason()).hasValue("stop");
    }

    @Test
    void readsUsageFromTheLastChunkOfAStreamSplitAnywhere() {
        String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\r\n\r\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":31900,\"completion_tokens\":512}}\n\n"
                + "data: [DONE]\n\n";
        UsageObserver observer = new UsageObserver("text/event-stream");
        byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i += 7) {
            byte[] chunk = java.util.Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + 7));
            observer.accept(chunk, chunk.length);
        }
        observer.finish();

        assertThat(observer.promptTokens()).hasValue(31_900L);
        assertThat(observer.completionTokens()).hasValue(512L);
        assertThat(observer.finishReason()).hasValue("length");
    }

    @Test
    void readsTheResponsesApiFormat() {
        UsageObserver observer = feed("text/event-stream", "event: response.completed\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[],"
                + "\"usage\":{\"input_tokens\":900,\"output_tokens\":12}}}\n\n");

        assertThat(observer.promptTokens()).hasValue(900L);
        assertThat(observer.completionTokens()).hasValue(12L);
        assertThat(observer.finishReason()).hasValue("completed");
    }

    @Test
    void reportsNothingWhenTheUpstreamSendsNoUsage() {
        UsageObserver stream = feed("text/event-stream", "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\ndata: [DONE]\n\n");
        UsageObserver other = feed("text/plain", "{\"usage\":{\"prompt_tokens\":1}}");
        UsageObserver broken = feed("application/json", "{not json");

        assertThat(stream.promptTokens()).isEmpty();
        assertThat(other.promptTokens()).isEmpty();
        assertThat(broken.promptTokens()).isEmpty();
    }

    private static UsageObserver feed(String contentType, String body) {
        UsageObserver observer = new UsageObserver(contentType);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        observer.accept(bytes, bytes.length);
        observer.finish();
        return observer;
    }
}
