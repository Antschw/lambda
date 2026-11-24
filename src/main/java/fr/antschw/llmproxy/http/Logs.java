package fr.antschw.llmproxy.http;

import java.time.Duration;

/** Formatting helpers for log messages. */
final class Logs {

    private Logs() {
    }

    /**
     * @param duration a duration
     * @return a compact form such as {@code 250ms}, {@code 1.5s} or {@code 2m30s}
     */
    static String duration(Duration duration) {
        long millis = duration.toMillis();
        if (millis < 1_000) {
            return millis + "ms";
        }
        if (millis < 60_000) {
            return millis % 1_000 == 0 ? millis / 1_000 + "s" : String.format("%.1fs", millis / 1_000.0);
        }
        long seconds = millis / 1_000;
        return seconds / 60 + "m" + (seconds % 60 == 0 ? "" : seconds % 60 + "s");
    }
}
