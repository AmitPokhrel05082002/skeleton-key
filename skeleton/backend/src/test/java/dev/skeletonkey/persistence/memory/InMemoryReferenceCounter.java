package dev.skeletonkey.persistence.memory;

import dev.skeletonkey.persistence.ReferenceCounter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryReferenceCounter implements ReferenceCounter {
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public long next(String prefix, int year) {
        return counters.computeIfAbsent(prefix + "|" + year, k -> new AtomicLong()).incrementAndGet();
    }
}
