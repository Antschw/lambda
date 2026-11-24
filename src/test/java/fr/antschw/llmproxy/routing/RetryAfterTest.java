package fr.antschw.llmproxy.routing;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetryAfterTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void readsRetryAfterSeconds() {
        assertThat(RetryAfter.fromHeaders(headers(Map.of("Retry-After", "12")), NOW)).hasValue(Duration.ofSeconds(12));
    }

    @Test
    void readsRetryAfterHttpDate() {
        HttpHeaders headers = headers(Map.of("Retry-After", "Thu, 01 Jan 2026 10:00:45 GMT"));

        assertThat(RetryAfter.fromHeaders(headers, NOW)).hasValue(Duration.ofSeconds(45));
    }

    @Test
    void prefersRetryAfterMs() {
        HttpHeaders headers = headers(Map.of("retry-after-ms", "1500", "Retry-After", "2"));

        assertThat(RetryAfter.fromHeaders(headers, NOW)).hasValue(Duration.ofMillis(1500));
    }

    @Test
    void usesTheLongestOpenAiResetHeader() {
        HttpHeaders headers = headers(Map.of("x-ratelimit-reset-requests", "1s", "x-ratelimit-reset-tokens", "6m0s"));

        assertThat(RetryAfter.fromHeaders(headers, NOW)).hasValue(Duration.ofMinutes(6));
    }

    @Test
    void returnsEmptyWithoutHintOrWithGarbage() {
        assertThat(RetryAfter.fromHeaders(headers(Map.of()), NOW)).isEmpty();
        assertThat(RetryAfter.fromHeaders(headers(Map.of("Retry-After", "later")), NOW)).isEmpty();
    }

    private static HttpHeaders headers(Map<String, String> values) {
        Map<String, List<String>> map = new java.util.HashMap<>();
        values.forEach((k, v) -> map.put(k, List.of(v)));
        return HttpHeaders.of(map, (k, v) -> true);
    }
}
