package fr.antschw.llmproxy.routing;

import java.time.Duration;
import java.time.Instant;

/**
 * Mutable health state of one {@link Target}. All methods are thread-safe.
 */
final class TargetState {

    private Instant cooldownUntil = Instant.EPOCH;
    private int consecutiveFailures;
    private boolean misconfigured;
    private long requests;
    private long successes;
    private long rateLimited;
    private long failures;

    synchronized boolean isCooling(Instant now) {
        return now.isBefore(cooldownUntil);
    }

    synchronized Instant cooldownUntil() {
        return cooldownUntil;
    }

    synchronized void recordAttempt() {
        requests++;
    }

    synchronized void recordSuccess() {
        successes++;
        consecutiveFailures = 0;
        misconfigured = false;
        cooldownUntil = Instant.EPOCH;
    }

    /**
     * Records a retryable failure and starts a cooldown.
     *
     * @param now         current time
     * @param hint        cooldown suggested by the upstream, or {@code null} to use exponential backoff
     * @param base        base cooldown for exponential backoff
     * @param max         upper bound for the cooldown
     * @param rateLimit   whether the failure was a 429
     * @return the cooldown applied
     */
    synchronized Duration recordFailure(Instant now, Duration hint, Duration base, Duration max, boolean rateLimit) {
        consecutiveFailures++;
        if (rateLimit) {
            rateLimited++;
        } else {
            failures++;
        }
        Duration cooldown = hint != null ? hint : backoff(base, consecutiveFailures);
        if (cooldown.compareTo(max) > 0) {
            cooldown = max;
        }
        extendCooldown(now.plus(cooldown));
        return cooldown;
    }

    synchronized void recordMisconfigured(Instant now, Duration cooldown) {
        failures++;
        consecutiveFailures++;
        misconfigured = true;
        extendCooldown(now.plus(cooldown));
    }

    synchronized Snapshot snapshot(Instant now) {
        return new Snapshot(isCooling(now), isCooling(now) ? cooldownUntil : null, consecutiveFailures,
                misconfigured, requests, successes, rateLimited, failures);
    }

    private void extendCooldown(Instant until) {
        if (until.isAfter(cooldownUntil)) {
            cooldownUntil = until;
        }
    }

    private static Duration backoff(Duration base, int failures) {
        int shift = Math.min(failures - 1, 20);
        return base.multipliedBy(1L << shift);
    }

    /** Point-in-time view of a target state, for the health endpoint. */
    record Snapshot(boolean cooling, Instant cooldownUntil, int consecutiveFailures, boolean misconfigured,
                    long requests, long successes, long rateLimited, long failures) {
    }
}
