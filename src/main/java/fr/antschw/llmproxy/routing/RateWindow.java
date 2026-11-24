package fr.antschw.llmproxy.routing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding-window request counter, used to stay below a provider's known requests-per-minute
 * quota instead of waiting for its 429. Thread-safe.
 */
final class RateWindow {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final int limit;
    private final Deque<Instant> timestamps = new ArrayDeque<>();

    RateWindow(int limit) {
        this.limit = limit;
    }

    /**
     * Takes a slot if one is free.
     *
     * @param now current time
     * @return {@code true} if the request may be sent
     */
    synchronized boolean tryAcquire(Instant now) {
        evict(now);
        if (timestamps.size() >= limit) {
            return false;
        }
        timestamps.addLast(now);
        return true;
    }

    /**
     * @param now current time
     * @return when the next slot frees up ({@code now} if one is free)
     */
    synchronized Instant nextFreeSlot(Instant now) {
        evict(now);
        return timestamps.size() < limit ? now : timestamps.peekFirst().plus(WINDOW);
    }

    private void evict(Instant now) {
        Instant threshold = now.minus(WINDOW);
        while (!timestamps.isEmpty() && !timestamps.peekFirst().isAfter(threshold)) {
            timestamps.removeFirst();
        }
    }
}
