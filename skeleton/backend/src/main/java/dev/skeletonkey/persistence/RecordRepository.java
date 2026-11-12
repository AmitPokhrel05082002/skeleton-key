package dev.skeletonkey.persistence;

import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

/** Port for business records stored as JSONB. */
public interface RecordRepository {
    void insert(RecordRow row);

    Optional<RecordRow> findById(UUID id);

    /** Replaces the data of a record. Returns false when the record does not exist. */
    boolean updateData(UUID id, Map<String, Object> data, Instant updatedAt);
}
