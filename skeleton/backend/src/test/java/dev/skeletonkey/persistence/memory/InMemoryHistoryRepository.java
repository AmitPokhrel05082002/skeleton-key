package dev.skeletonkey.persistence.memory;

import dev.skeletonkey.domain.model.HistoryEntry;
import dev.skeletonkey.persistence.HistoryRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Append-only twin. Sorting is stable, so equal event times keep insertion order. */
public class InMemoryHistoryRepository implements HistoryRepository {
    private final Map<UUID, List<HistoryEntry>> byInstance = new HashMap<>();

    @Override
    public synchronized void append(UUID instanceId, HistoryEntry entry) {
        byInstance.computeIfAbsent(instanceId, k -> new ArrayList<>()).add(entry);
    }

    @Override
    public synchronized List<HistoryEntry> findByInstance(UUID instanceId) {
        List<HistoryEntry> copy = new ArrayList<>(byInstance.getOrDefault(instanceId, List.of()));
        copy.sort(Comparator.comparing(HistoryEntry::eventTime, Comparator.nullsFirst(Comparator.naturalOrder())));
        return copy;
    }
}
