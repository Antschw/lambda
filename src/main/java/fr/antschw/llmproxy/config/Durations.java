package fr.antschw.llmproxy.config;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses human-friendly durations.
 *
 * <p>Accepted forms: ISO-8601 ({@code PT30S}), a bare number of seconds ({@code 30}, {@code 1.5}),
 * or a sequence of number/unit pairs ({@code 500ms}, {@code 30s}, {@code 5m}, {@code 1h},
 * {@code 6m0s}, {@code 1m30.5s}). The last form is also the format OpenAI uses in its
 * {@code x-ratelimit-reset-*} headers.
 */
public final class Durations {

    private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?");
    private static final Pattern PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s|m|h|d)");
    private static final Pattern UNITS = Pattern.compile("((\\d+(?:\\.\\d+)?)(ms|s|m|h|d))+");

    private Durations() {
    }

    /**
     * Parses a duration.
     *
     * @param text the text to parse
     * @return the duration
     * @throws IllegalArgumentException if the text is not a valid duration
     */
    public static Duration parse(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty duration");
        }
        if (value.startsWith("pt") || value.startsWith("p")) {
            try {
                return Duration.parse(value.toUpperCase(Locale.ROOT));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid ISO-8601 duration: " + text, e);
            }
        }
        if (NUMBER.matcher(value).matches()) {
            return fromSeconds(Double.parseDouble(value));
        }
        if (!UNITS.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid duration: " + text);
        }
        long millis = 0;
        Matcher matcher = PART.matcher(value);
        while (matcher.find()) {
            double amount = Double.parseDouble(matcher.group(1));
            millis += Math.round(amount * switch (matcher.group(2)) {
                case "ms" -> 1;
                case "s" -> 1_000;
                case "m" -> 60_000;
                case "h" -> 3_600_000;
                default -> 86_400_000; // "d"
            });
        }
        return Duration.ofMillis(millis);
    }

    /**
     * Converts a (possibly fractional) number of seconds into a duration.
     *
     * @param seconds number of seconds
     * @return the duration, rounded to the millisecond
     */
    public static Duration fromSeconds(double seconds) {
        return Duration.ofMillis(Math.round(seconds * 1_000));
    }
}
