package dev.skeletonkey.persistence.memory;

import dev.skeletonkey.persistence.RecordRepository;
import dev.skeletonkey.persistence.RecordRow;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRecordRepository implements RecordRepository {
    private final Map<UUID, RecordRow> rows = new ConcurrentHashMap<>();

    @Override
    public void insert(RecordRow row) {
        if (rows.putIfAbsent(row.id(), row) != null) {
            throw new IllegalStateException("Duplicate record id " + row.id());
        }
    }

    @Override
    public Optional<RecordRow> findById(UUID id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public boolean updateData(UUID id, Map<String, Object> data, Instant updatedAt) {
        return rows.computeIfPresent(id, (k, r) -> r.withData(data, updatedAt)) != null;
    }
}
