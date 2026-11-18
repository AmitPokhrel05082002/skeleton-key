package dev.skeletonkey.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/** Property 9: exactly {@code limit} allowed requests per address in any 60 s window; addresses are independent. */
class TrackRateLimiterPropertyTest {

    static final class TestClock extends Clock {
        long millis;
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    record Event(String key, long gapMillis) {}

    @Provide
    Arbitrary<List<Event>> events() {
        return Combinators.combine(Arbitraries.of("10.0.0.1", "10.0.0.2", "203.0.113.9"),
                        Arbitraries.longs().between(0, 20_000))
                .as(Event::new).list().ofMaxSize(200);
    }

    @Property(tries = 300)
    void matchesTheReferenceModel(@ForAll @IntRange(min = 1, max = 12) int limit, @ForAll("events") List<Event> events) {
        TestClock clock = new TestClock();
        clock.millis = 1_000_000;
        TrackRateLimiter limiter = new TrackRateLimiter(limit, 100, clock);
        Map<String, ArrayDeque<Long>> model = new HashMap<>();
        for (Event e : events) {
            clock.millis += e.gapMillis();
            long now = clock.millis;
            ArrayDeque<Long> allowed = model.computeIfAbsent(e.key(), k -> new ArrayDeque<>());
            allowed.removeIf(t -> t <= now - 60_000);
            boolean expect = allowed.size() < limit;
            TrackRateLimiter.Decision d = limiter.tryAcquire(e.key());
            assertThat(d.allowed()).isEqualTo(expect);
            if (expect) {
                allowed.addLast(now);
            } else {
                assertThat(d.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
                // Waiting the advertised time is enough.
                long reopen = allowed.peekFirst() + 60_000;
                assertThat(now + d.retryAfterSeconds() * 1000).isGreaterThanOrEqualTo(reopen);
            }
        }
    }

    @Example
    void boundsTheNumberOfTrackedAddresses() {
        TestClock clock = new TestClock();
        TrackRateLimiter limiter = new TrackRateLimiter(10, 100, clock);
        for (int i = 0; i < 5_000; i++) {
            limiter.tryAcquire("198.51." + (i / 256) + "." + (i % 256));
        }
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(100);
    }

    @Example
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> new TrackRateLimiter(0, 100, Clock.systemUTC()))
                .hasMessageContaining("per-minute");
        assertThatThrownBy(() -> new TrackRateLimiter(10, 99, Clock.systemUTC()))
                .hasMessageContaining("max-clients");
    }
}
