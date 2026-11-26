package fr.antschw.llmproxy.routing;

import fr.antschw.llmproxy.config.ProxyConfig;
import fr.antschw.llmproxy.config.ProxyConfig.Upstream;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Chooses which {@link Target} serves a request and tracks target health.
 *
 * <p>Targets of a route are used in round-robin order. A target is skipped while it is cooling
 * down (after a 429 or an error) or while its upstream's proactive request-per-minute window is
 * full. Health is tracked per target, so a target that appears in several virtual models shares
 * its cooldown across them.
 *
 * <p>Passthrough routes (one per real model name, over every upstream exposing it) are rebuilt by
 * {@link #updateModels(Map)} when model discovery runs; target health and round-robin positions
 * survive these updates. Thread-safe.
 */
public final class TargetPool {

    /** Below this estimate, prompt token comparisons are too noisy to suspect a truncation. */
    static final long TRUNCATION_MIN_ESTIMATE = 2_000;

    private final ProxyConfig config;
    private final Clock clock;
    private final Map<String, Route> virtualRoutes = new LinkedHashMap<>();
    private volatile Map<String, Route> passthroughRoutes = Map.of();
    private final Map<String, TargetState> states = new ConcurrentHashMap<>();
    private final Map<String, RateWindow> windows = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> cursors = new ConcurrentHashMap<>();
    private volatile Map<String, Integer> advertisedLimits = Map.of();

    /**
     * @param config validated configuration
     * @param clock  time source
     */
    public TargetPool(ProxyConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
        Map<String, Upstream> upstreams = config.upstreams().stream()
                .collect(Collectors.toMap(Upstream::name, Function.identity()));

        config.models().forEach((name, routes) -> virtualRoutes.put(name, new Route(name, true,
                routes.stream().map(r -> new Target(upstreams.get(r.upstream()), r.model())).toList())));

        for (Upstream upstream : config.upstreams()) {
            upstream.maxRequestsPerMinute().ifPresent(rpm -> windows.put(upstream.name(), new RateWindow(rpm)));
        }
        virtualRoutes.values().forEach(route -> route.targets()
                .forEach(t -> states.computeIfAbsent(t.id(), k -> new TargetState())));
        updateModels(Map.of());
    }

    /**
     * Rebuilds passthrough routes from the configured {@code passthroughModels} plus the models
     * discovered on each upstream. A model exposed by N upstreams gets a route over those N
     * targets, in upstream declaration order. Virtual model names take precedence.
     *
     * @param discovered model ids by upstream name (upstreams missing from the map contribute only
     *                   their configured passthrough models)
     */
    public void updateModels(Map<String, ? extends Collection<String>> discovered) {
        updateModels(discovered, Map.of());
    }

    /**
     * Same as {@link #updateModels(Map)}, with the context sizes the upstreams advertise.
     *
     * @param discovered model ids by upstream name
     * @param limits     advertised context size by upstream name, then model id
     */
    public synchronized void updateModels(Map<String, ? extends Collection<String>> discovered,
                                          Map<String, Map<String, Integer>> limits) {
        Map<String, Integer> advertised = new ConcurrentHashMap<>();
        limits.forEach((upstream, models) -> models.forEach((model, tokens) ->
                advertised.put(upstream + "/" + model, tokens)));
        advertisedLimits = advertised;
        Map<String, List<Target>> byModel = new TreeMap<>();
        for (Upstream upstream : config.upstreams()) {
            Set<String> models = new LinkedHashSet<>(upstream.passthroughModels());
            Collection<String> found = discovered.get(upstream.name());
            if (found != null) {
                models.addAll(found);
            }
            for (String model : models) {
                if (!virtualRoutes.containsKey(model)) {
                    byModel.computeIfAbsent(model, k -> new ArrayList<>()).add(new Target(upstream, model));
                }
            }
        }
        Map<String, Route> routes = new LinkedHashMap<>();
        byModel.forEach((model, targets) -> {
            targets.forEach(t -> states.computeIfAbsent(t.id(), k -> new TargetState()));
            routes.put(model, new Route(model, false, List.copyOf(targets)));
        });
        passthroughRoutes = Collections.unmodifiableMap(routes);
    }

    /**
     * Finds the route serving a requested model: a virtual model first, then a passthrough model,
     * then the configured default model.
     *
     * @param requestedModel model name sent by the client, possibly {@code null}
     * @return the route, or empty if no route serves this model
     */
    public Optional<Route> resolve(String requestedModel) {
        if (requestedModel != null) {
            Route route = virtualRoutes.get(requestedModel);
            if (route == null) {
                route = passthroughRoutes.get(requestedModel);
            }
            if (route != null) {
                return Optional.of(route);
            }
        }
        return config.defaultModel().map(virtualRoutes::get);
    }

    /**
     * Picks the next available target of a route.
     *
     * @param route    the route
     * @param excluded targets that must not be used for this request (e.g. they answered 404 to it)
     * @return the chosen target, when one will be available, or that none is left
     */
    public Selection acquire(Route route, Set<Target> excluded) {
        Instant now = clock.instant();
        List<Target> targets = route.targets();
        int start = Math.floorMod(cursors.computeIfAbsent(route.name(), k -> new AtomicInteger()).getAndIncrement(),
                targets.size());
        Instant earliest = null;
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get((start + i) % targets.size());
            if (excluded.contains(target)) {
                continue;
            }
            TargetState state = states.get(target.id());
            if (state.isCooling(now)) {
                earliest = min(earliest, state.cooldownUntil());
                continue;
            }
            RateWindow window = windows.get(target.upstream().name());
            if (window != null && !window.tryAcquire(now)) {
                earliest = min(earliest, window.nextFreeSlot(now));
                continue;
            }
            state.recordAttempt();
            return new Selection.Acquired(target);
        }
        return earliest == null ? new Selection.Exhausted() : new Selection.Unavailable(earliest);
    }

    /**
     * Tells when a target of the route will be available, without reserving it.
     *
     * @param route the route
     * @return the earliest availability ({@code now} if a target is available now)
     */
    public Instant earliestAvailability(Route route) {
        Instant now = clock.instant();
        Instant earliest = null;
        for (Target target : route.targets()) {
            Instant at = states.get(target.id()).cooldownUntil();
            RateWindow window = windows.get(target.upstream().name());
            if (window != null) {
                Instant slot = window.nextFreeSlot(now);
                at = slot.isAfter(at) ? slot : at;
            }
            earliest = min(earliest, at.isBefore(now) ? now : at);
        }
        return earliest == null ? now : earliest;
    }

    /**
     * Effective context limit of a target, in tokens: the upstream's {@code modelContextTokens}
     * (first matching pattern), then its {@code maxContextTokens}, then the size advertised by its
     * {@code /models}, then, if {@code context.learnFromTruncation} is on, the size deduced from
     * suspected truncations.
     *
     * @param target the target
     * @return the limit, or empty if unknown (unlimited)
     */
    public Optional<Long> contextLimit(Target target) {
        Upstream upstream = target.upstream();
        for (Map.Entry<String, Integer> entry : upstream.modelContextTokens().entrySet()) {
            if (new ModelFilter(List.of(entry.getKey()), List.of()).test(target.model())) {
                return Optional.of(entry.getValue().longValue());
            }
        }
        if (upstream.maxContextTokens().isPresent()) {
            return Optional.of(upstream.maxContextTokens().get().longValue());
        }
        Integer advertised = advertisedLimits.get(target.id());
        if (advertised != null) {
            return Optional.of(advertised.longValue());
        }
        TargetState state = states.get(target.id());
        if (config.context().learnFromTruncation() && state != null && state.learnedLimit() > 0) {
            return Optional.of(state.learnedLimit());
        }
        return Optional.empty();
    }

    /**
     * Targets of a route too small for a request. When no target is large enough, every target
     * except those with the largest limit is returned, since the size is only an estimate and the
     * provider gets the last word.
     *
     * @param route  the route
     * @param tokens estimated tokens the request needs (prompt and requested output)
     * @return targets to exclude for this request, possibly empty
     */
    public Set<Target> tooSmallFor(Route route, long tokens) {
        Set<Target> tooSmall = new LinkedHashSet<>();
        long largest = 0;
        boolean unlimited = false;
        for (Target target : route.targets()) {
            Optional<Long> limit = contextLimit(target);
            if (limit.isEmpty()) {
                unlimited = true;
            } else {
                largest = Math.max(largest, limit.get());
                if (limit.get() < tokens) {
                    tooSmall.add(target);
                }
            }
        }
        if (!unlimited && tooSmall.size() == route.targets().size()) {
            long max = largest;
            tooSmall.removeIf(t -> contextLimit(t).orElse(0L) == max);
        }
        return tooSmall;
    }

    /**
     * Records the prompt size an upstream reported and whether it looks like a silent truncation:
     * far fewer tokens than the proxy estimated it sent.
     *
     * @param target          the target
     * @param estimatedTokens prompt tokens estimated by the proxy
     * @param promptTokens    prompt tokens reported by the upstream
     * @return whether a silent truncation is suspected
     */
    public boolean onUsage(Target target, long estimatedTokens, long promptTokens) {
        boolean truncated = estimatedTokens >= TRUNCATION_MIN_ESTIMATE
                && promptTokens < estimatedTokens * config.context().truncationRatio();
        states.get(target.id()).recordUsage(promptTokens, truncated);
        return truncated;
    }

    /**
     * @param model a model name served by the proxy
     * @return the largest context limit among its targets, or empty if one is unknown or none is set
     */
    public Optional<Long> largestContextLimit(String model) {
        Route route = virtualRoutes.containsKey(model) ? virtualRoutes.get(model) : passthroughRoutes.get(model);
        if (route == null) {
            return Optional.empty();
        }
        long largest = 0;
        for (Target target : route.targets()) {
            Optional<Long> limit = contextLimit(target);
            if (limit.isEmpty()) {
                return Optional.empty();
            }
            largest = Math.max(largest, limit.get());
        }
        return Optional.of(largest);
    }

    /**
     * Records a successful call; the target's failure counter is reset.
     *
     * @param target the target
     */
    public void onSuccess(Target target) {
        states.get(target.id()).recordSuccess();
    }

    /**
     * Records a retryable failure (429, 5xx, timeout, network error) and puts the target in cooldown.
     *
     * @param target    the target
     * @param hint      cooldown suggested by the upstream, if any
     * @param rateLimit whether the failure was a 429
     * @return the cooldown applied
     */
    public Duration onRetryableFailure(Target target, Optional<Duration> hint, boolean rateLimit) {
        ProxyConfig.Retry retry = config.retry();
        return states.get(target.id()).recordFailure(clock.instant(), hint.orElse(null),
                retry.defaultCooldown(), retry.maxCooldown(), rateLimit);
    }

    /**
     * Records a configuration problem (401, 403, 404): the target is set aside for a long time.
     *
     * @param target the target
     * @return the cooldown applied
     */
    public Duration onMisconfigured(Target target) {
        Duration cooldown = config.retry().misconfiguredCooldown();
        states.get(target.id()).recordMisconfigured(clock.instant(), cooldown);
        return cooldown;
    }

    /**
     * @param model a model name served by the proxy
     * @return names of the upstreams serving it, without duplicates, or an empty list
     */
    public List<String> upstreamsOf(String model) {
        Route route = virtualRoutes.containsKey(model) ? virtualRoutes.get(model) : passthroughRoutes.get(model);
        return route == null ? List.of() : route.upstreams();
    }

    /** @return virtual and passthrough model names, virtual first, passthrough sorted by name */
    public List<String> modelNames() {
        List<String> names = new ArrayList<>(virtualRoutes.keySet());
        names.addAll(passthroughRoutes.keySet());
        return names;
    }

    /** @return the health of every target, by route */
    public Map<String, List<TargetStatus>> status() {
        Instant now = clock.instant();
        Map<String, List<TargetStatus>> result = new LinkedHashMap<>();
        for (Route route : allRoutes()) {
            result.put(route.name(), route.targets().stream()
                    .map(t -> TargetStatus.of(t, states.get(t.id()).snapshot(now), contextLimit(t)))
                    .toList());
        }
        return result;
    }

    private List<Route> allRoutes() {
        List<Route> routes = new ArrayList<>(virtualRoutes.values());
        routes.addAll(passthroughRoutes.values());
        return routes;
    }

    private static Instant min(Instant a, Instant b) {
        return a == null || b.isBefore(a) ? b : a;
    }

    /**
     * The set of targets serving one client-facing model name.
     *
     * @param name    model name the client uses
     * @param virtual whether it is a virtual model (the name is rewritten) or a passthrough model
     * @param targets targets in round-robin order
     */
    public record Route(String name, boolean virtual, List<Target> targets) {

        /** @return names of the upstreams of this route, without duplicates, in route order */
        public List<String> upstreams() {
            return targets.stream().map(t -> t.upstream().name()).distinct().toList();
        }
    }

    /** Result of {@link #acquire(Route)}. */
    public sealed interface Selection {

        /** @param target the target to call */
        record Acquired(Target target) implements Selection {
        }

        /** @param availableAt earliest time a target of the route becomes available */
        record Unavailable(Instant availableAt) implements Selection {
        }

        /** Every target of the route is excluded for this request. */
        record Exhausted() implements Selection {
        }
    }

    /**
     * Public view of a target's health.
     *
     * @param target              target id
     * @param available           whether it can be used now
     * @param cooldownUntil       end of the current cooldown, or {@code null}
     * @param consecutiveFailures failures since the last success
     * @param misconfigured       whether the last failure was a 401/403/404
     * @param requests            attempts sent
     * @param successes           successful attempts
     * @param rateLimited         attempts answered with 429
     * @param failures            other failed attempts
     * @param contextLimit        effective context limit, if known
     * @param maxPromptTokensSeen largest prompt size the upstream reported
     * @param truncationsSuspected responses that looked silently truncated
     */
    public record TargetStatus(String target, boolean available, Instant cooldownUntil, int consecutiveFailures,
                               boolean misconfigured, long requests, long successes, long rateLimited,
                               long failures, Optional<Long> contextLimit, long maxPromptTokensSeen,
                               long truncationsSuspected) {

        static TargetStatus of(Target target, TargetState.Snapshot s, Optional<Long> contextLimit) {
            return new TargetStatus(target.id(), !s.cooling(), s.cooldownUntil(), s.consecutiveFailures(),
                    s.misconfigured(), s.requests(), s.successes(), s.rateLimited(), s.failures(), contextLimit,
                    s.maxPromptTokensSeen(), s.truncationsSuspected());
        }
    }
}
