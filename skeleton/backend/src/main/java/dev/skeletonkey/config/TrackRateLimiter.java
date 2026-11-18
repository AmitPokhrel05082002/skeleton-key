package dev.skeletonkey.config;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sliding-window limiter: at most {@code perMinute} allowed requests per key in any 60 s. Only allowed requests are
 * recorded, so the state per key never exceeds {@code perMinute} timestamps, and the number of keys is capped at
 * {@code maxKeys} (least recently seen evicted). In memory, per instance.
 */
public final class TrackRateLimiter {

    static final long WINDOW_MILLIS = 60_000L;

    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private final int perMinute;
    private final Clock clock;
    private final LinkedHashMap<String, ArrayDeque<Long>> hits;

    public TrackRateLimiter(int perMinute, int maxKeys, Clock clock) {
        if (perMinute < 1) {
            throw new IllegalArgumentException("skeletonkey.track.rate-limit.per-minute must be at least 1");
        }
        if (maxKeys < 100) {
            throw new IllegalArgumentException("skeletonkey.track.rate-limit.max-clients must be at least 100");
        }
        this.perMinute = perMinute;
        this.clock = clock;
        this.hits = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ArrayDeque<Long>> eldest) {
                return size() > maxKeys;
            }
        };
    }

    public synchronized Decision tryAcquire(String key) {
        long now = clock.millis();
        ArrayDeque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!q.isEmpty() && q.peekFirst() <= now - WINDOW_MILLIS) {
            q.pollFirst();
        }
        if (q.size() < perMinute) {
            q.addLast(now);
            return new Decision(true, 0);
        }
        long waitMillis = q.peekFirst() + WINDOW_MILLIS - now;
        return new Decision(false, Math.max(1, (waitMillis + 999) / 1000));
    }

    synchronized int trackedKeys() {
        return hits.size();
    }
}
