package dev.skeletonkey.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port for business records stored as JSONB. */
public interface RecordRepository {

    /** One page of rows plus the total number of matching rows. */
    record Page(List<RecordRow> rows, long total) {}

    void insert(RecordRow row);

    Optional<RecordRow> findById(UUID id);

    /** Replaces the data of a record. Returns false when the record does not exist. */
    boolean updateData(UUID id, Map<String, Object> data, Instant updatedAt);

    /** Filtered, visibility-restricted, sorted and paged listing; the total counts all matching rows. */
    Page search(RecordQuery query);
}
