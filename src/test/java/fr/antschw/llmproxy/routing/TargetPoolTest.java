package fr.antschw.llmproxy.routing;

import fr.antschw.llmproxy.config.ConfigLoader;
import fr.antschw.llmproxy.routing.TargetPool.Route;
import fr.antschw.llmproxy.routing.TargetPool.Selection;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TargetPoolTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));

    @Test
    void cyclesThroughTargetsInOrder() {
        TargetPool pool = pool("");
        Route route = pool.resolve("coder").orElseThrow();

        assertThat(pick(pool, route, 6)).containsExactly("a/ma", "b/mb", "c/mc", "a/ma", "b/mb", "c/mc");
    }

    @Test
    void skipsCoolingTargetsUntilTheirCooldownEnds() {
        TargetPool pool = pool("");
        Route route = pool.resolve("coder").orElseThrow();
        Target a = route.targets().getFirst();

        assertThat(pool.onRetryableFailure(a, Optional.of(Duration.ofSeconds(10)), true)).isEqualTo(Duration.ofSeconds(10));

        assertThat(pick(pool, route, 4)).doesNotContain("a/ma");
        clock.advance(Duration.ofSeconds(10));
        assertThat(pick(pool, route, 3)).contains("a/ma");
    }

    @Test
    void appliesExponentialBackoffCappedByMaxCooldown() {
        TargetPool pool = pool("\"retry\": {\"defaultCooldown\": \"10s\", \"maxCooldown\": \"35s\"},");
        Target a = pool.resolve("coder").orElseThrow().targets().getFirst();

        assertThat(pool.onRetryableFailure(a, Optional.empty(), false)).isEqualTo(Duration.ofSeconds(10));
        assertThat(pool.onRetryableFailure(a, Optional.empty(), false)).isEqualTo(Duration.ofSeconds(20));
        assertThat(pool.onRetryableFailure(a, Optional.empty(), false)).isEqualTo(Duration.ofSeconds(35));
        assertThat(pool.onRetryableFailure(a, Optional.of(Duration.ofHours(1)), true)).isEqualTo(Duration.ofSeconds(35));

        pool.onSuccess(a);
        assertThat(pool.onRetryableFailure(a, Optional.empty(), false)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void reportsTheEarliestAvailabilityWhenAllTargetsAreCooling() {
        TargetPool pool = pool("");
        Route route = pool.resolve("coder").orElseThrow();
        List<Duration> cooldowns = List.of(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(60));
        for (int i = 0; i < 3; i++) {
            pool.onRetryableFailure(route.targets().get(i), Optional.of(cooldowns.get(i)), true);
        }

        Selection selection = pool.acquire(route, Set.of());

        assertThat(selection).isEqualTo(new Selection.Unavailable(clock.instant().plusSeconds(5)));
        assertThat(pool.earliestAvailability(route)).isEqualTo(clock.instant().plusSeconds(5));
    }

    @Test
    void reportsExhaustionWhenEveryTargetIsExcluded() {
        TargetPool pool = pool("");
        Route route = pool.resolve("coder").orElseThrow();

        assertThat(pool.acquire(route, Set.copyOf(route.targets()))).isInstanceOf(Selection.Exhausted.class);
    }

    @Test
    void respectsTheProactiveRequestsPerMinuteLimit() {
        TargetPool pool = new TargetPool(new ConfigLoader(n -> null).parse("""
                {
                  "upstreams": [
                    {"name": "a", "baseUrl": "https://a/v1", "apiKey": "k", "maxRequestsPerMinute": 2},
                    {"name": "b", "baseUrl": "https://b/v1", "apiKey": "k", "maxRequestsPerMinute": 1}
                  ],
                  "models": {"coder": [{"upstream": "a", "model": "ma"}, {"upstream": "b", "model": "mb"}]}
                }"""), clock);
        Route route = pool.resolve("coder").orElseThrow();

        assertThat(pick(pool, route, 3)).containsExactly("a/ma", "b/mb", "a/ma");
        assertThat(pool.acquire(route, Set.of())).isInstanceOf(Selection.Unavailable.class);

        clock.advance(Duration.ofSeconds(61));
        assertThat(pool.acquire(route, Set.of())).isInstanceOf(Selection.Acquired.class);
    }

    @Test
    void resolvesVirtualThenPassthroughThenDefaultModels() {
        TargetPool pool = new TargetPool(new ConfigLoader(n -> null).parse("""
                {
                  "upstreams": [{"name": "a", "baseUrl": "https://a/v1", "apiKey": "k", "passthroughModels": ["raw"]}],
                  "models": {"coder": [{"upstream": "a", "model": "ma"}]},
                  "defaultModel": "coder"
                }"""), clock);

        assertThat(pool.resolve("coder")).get().extracting(Route::virtual).isEqualTo(true);
        assertThat(pool.resolve("raw")).get().extracting(Route::virtual).isEqualTo(false);
        assertThat(pool.resolve("unknown")).get().extracting(Route::name).isEqualTo("coder");
        assertThat(pool.resolve(null)).get().extracting(Route::name).isEqualTo("coder");
        assertThat(pool.modelNames()).containsExactly("coder", "raw");
    }

    @Test
    void sharesHealthBetweenVirtualModelsUsingTheSameTarget() {
        TargetPool pool = new TargetPool(new ConfigLoader(n -> null).parse("""
                {
                  "upstreams": [{"name": "a", "baseUrl": "https://a/v1", "apiKey": "k"}],
                  "models": {"coder": [{"upstream": "a", "model": "m"}], "fast": [{"upstream": "a", "model": "m"}]}
                }"""), clock);
        Target target = pool.resolve("coder").orElseThrow().targets().getFirst();

        pool.onRetryableFailure(target, Optional.of(Duration.ofSeconds(5)), true);

        assertThat(pool.acquire(pool.resolve("fast").orElseThrow(), Set.of())).isInstanceOf(Selection.Unavailable.class);
    }

    @Test
    void buildsOneRoutePerDiscoveredModelOverTheUpstreamsExposingIt() {
        TargetPool pool = pool("");

        pool.updateModels(Map.of("a", Set.of("m1", "m2"), "b", Set.of("m1"), "c", Set.of("m1", "m3")));

        assertThat(pool.modelNames()).containsExactly("coder", "m1", "m2", "m3");
        assertThat(pool.upstreamsOf("m1")).containsExactly("a", "b", "c");
        assertThat(pool.upstreamsOf("m2")).containsExactly("a");
        assertThat(pool.upstreamsOf("m3")).containsExactly("c");
        assertThat(pool.resolve("m1")).get().extracting(Route::virtual).isEqualTo(false);
    }

    @Test
    void keepsCooldownsAcrossModelUpdatesAndDropsVanishedModels() {
        TargetPool pool = pool("");
        pool.updateModels(Map.of("a", Set.of("m1"), "b", Set.of("m1", "m2")));
        Target aM1 = pool.resolve("m1").orElseThrow().targets().getFirst();
        pool.onRetryableFailure(aM1, Optional.of(Duration.ofSeconds(30)), true);

        pool.updateModels(Map.of("a", Set.of("m1"), "b", Set.of("m1")));

        Route m1 = pool.resolve("m1").orElseThrow();
        assertThat(pick(pool, m1, 3)).containsOnly("b/m1");
        assertThat(pool.resolve("m2")).isEmpty();
    }

    @Test
    void keepsConfiguredPassthroughModelsWhateverIsDiscovered() {
        TargetPool pool = new TargetPool(new ConfigLoader(n -> null).parse("""
                {
                  "upstreams": [{"name": "a", "baseUrl": "https://a/v1", "apiKey": "k", "passthroughModels": ["static"]}]
                }"""), clock);

        pool.updateModels(Map.of("a", Set.of("found")));

        assertThat(pool.modelNames()).containsExactly("found", "static");
    }

    @Test
    void resolvesContextLimitsFromModelPatternsThenUpstreamThenAdvertisedSizes() {
        TargetPool pool = new TargetPool(new ConfigLoader(n -> null).parse("""
                {
                  "upstreams": [
                    {"name": "a", "baseUrl": "https://a/v1", "apiKey": "k", "maxContextTokens": 32000,
                     "modelContextTokens": {"*-128k": 128000}},
                    {"name": "b", "baseUrl": "https://b/v1", "apiKey": "k"}
                  ]
                }"""), clock);
        pool.updateModels(Map.of("a", Set.of("m", "m-128k"), "b", Set.of("m", "x")),
                Map.of("a", Map.of("m", 64_000), "b", Map.of("m", 16_000)));

        assertThat(pool.contextLimit(target(pool, "m-128k", "a"))).hasValue(128_000L);
        assertThat(pool.contextLimit(target(pool, "m", "a"))).hasValue(32_000L);
        assertThat(pool.contextLimit(target(pool, "m", "b"))).hasValue(16_000L);
        assertThat(pool.contextLimit(target(pool, "x", "b"))).isEmpty();
        assertThat(pool.largestContextLimit("m")).hasValue(32_000L);
        assertThat(pool.largestContextLimit("x")).isEmpty();
    }

    @Test
    void excludesTargetsTooSmallForARequestButNeverAllOfThem() {
        TargetPool pool = new TargetPool(new ConfigLoader(n -> null).parse("""
                {
                  "upstreams": [
                    {"name": "a", "baseUrl": "https://a/v1", "apiKey": "k", "maxContextTokens": 8000},
                    {"name": "b", "baseUrl": "https://b/v1", "apiKey": "k", "maxContextTokens": 32000},
                    {"name": "c", "baseUrl": "https://c/v1", "apiKey": "k", "maxContextTokens": 32000}
                  ]
                }"""), clock);
        pool.updateModels(Map.of("a", Set.of("m"), "b", Set.of("m"), "c", Set.of("m")));
        Route route = pool.resolve("m").orElseThrow();

        assertThat(pool.tooSmallFor(route, 5_000)).isEmpty();
        assertThat(pool.tooSmallFor(route, 20_000)).extracting(Target::id).containsExactly("a/m");
        assertThat(pool.tooSmallFor(route, 50_000)).extracting(Target::id).containsExactly("a/m");
    }

    @Test
    void suspectsTruncationWhenTheUpstreamCountsFarFewerTokens() {
        TargetPool pool = pool("");
        Target a = pool.resolve("coder").orElseThrow().targets().getFirst();

        assertThat(pool.onUsage(a, 10_000, 7_000)).isFalse();
        assertThat(pool.onUsage(a, 10_000, 5_000)).isTrue();
        assertThat(pool.onUsage(a, 1_000, 10)).isFalse();
        assertThat(pool.status().get("coder").getFirst().truncationsSuspected()).isEqualTo(1);
        assertThat(pool.status().get("coder").getFirst().maxPromptTokensSeen()).isEqualTo(7_000);
        assertThat(pool.contextLimit(a)).isEmpty();
    }

    private static Target target(TargetPool pool, String model, String upstream) {
        return pool.resolve(model).orElseThrow().targets().stream()
                .filter(t -> t.upstream().name().equals(upstream)).findFirst().orElseThrow();
    }

    private TargetPool pool(String extra) {
        return new TargetPool(new ConfigLoader(n -> null).parse("{" + extra + """
                "upstreams": [
                  {"name": "a", "baseUrl": "https://a/v1", "apiKey": "k"},
                  {"name": "b", "baseUrl": "https://b/v1", "apiKey": "k"},
                  {"name": "c", "baseUrl": "https://c/v1", "apiKey": "k"}
                ],
                "models": {"coder": [
                  {"upstream": "a", "model": "ma"}, {"upstream": "b", "model": "mb"}, {"upstream": "c", "model": "mc"}
                ]}
                }"""), clock);
    }

    private static List<String> pick(TargetPool pool, Route route, int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (pool.acquire(route, Set.of()) instanceof Selection.Acquired(Target target)) {
                ids.add(target.id());
            }
        }
        return ids;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
