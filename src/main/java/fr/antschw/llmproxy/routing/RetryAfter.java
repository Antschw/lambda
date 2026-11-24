package fr.antschw.llmproxy.routing;

import fr.antschw.llmproxy.config.Durations;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Extracts how long to wait before retrying an upstream from its response headers.
 *
 * <p>Supported headers, in order of preference:
 * <ul>
 *     <li>{@code retry-after-ms}: milliseconds (sent by some OpenAI-compatible servers)</li>
 *     <li>{@code retry-after}: seconds or an HTTP date (RFC 9110)</li>
 *     <li>{@code x-ratelimit-reset-requests} / {@code x-ratelimit-reset-tokens}: OpenAI style
 *         ({@code 1s}, {@code 6m0s}, {@code 20ms}); the longest one wins</li>
 * </ul>
 */
public final class RetryAfter {

    private static final List<String> RESET_HEADERS = List.of("x-ratelimit-reset-requests", "x-ratelimit-reset-tokens");

    private RetryAfter() {
    }

    /**
     * @param headers upstream response headers
     * @param now     current time, used for HTTP dates
     * @return the suggested wait, or empty if the headers give none
     */
    public static Optional<Duration> fromHeaders(HttpHeaders headers, Instant now) {
        Optional<Duration> millis = headers.firstValue("retry-after-ms").flatMap(RetryAfter::parseMillis);
        if (millis.isPresent()) {
            return millis;
        }
        Optional<Duration> retryAfter = headers.firstValue("retry-after").flatMap(v -> parseRetryAfter(v, now));
        if (retryAfter.isPresent()) {
            return retryAfter;
        }
        return RESET_HEADERS.stream()
                .map(headers::firstValue)
                .flatMap(Optional::stream)
                .map(RetryAfter::parseDuration)
                .flatMap(Optional::stream)
                .max(Duration::compareTo);
    }

    static Optional<Duration> parseRetryAfter(String value, Instant now) {
        String trimmed = value.trim();
        try {
            return Optional.of(Durations.fromSeconds(Double.parseDouble(trimmed))).filter(d -> !d.isNegative());
        } catch (NumberFormatException ignored) {
            // Not a number: try an HTTP date.
        }
        try {
            Instant at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration wait = Duration.between(now, at);
            return Optional.of(wait.isNegative() ? Duration.ZERO : wait);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<Duration> parseMillis(String value) {
        try {
            return Optional.of(Duration.ofMillis(Math.round(Double.parseDouble(value.trim()))))
                    .filter(d -> !d.isNegative());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Duration> parseDuration(String value) {
        try {
            return Optional.of(Durations.parse(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
