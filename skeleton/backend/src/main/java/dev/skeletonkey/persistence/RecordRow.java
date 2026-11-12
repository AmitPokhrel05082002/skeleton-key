package dev.skeletonkey.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A stored business record. Field values live in {@code data} (JSONB), keyed by field code. */
public record RecordRow(UUID id, String appCode, int defVersion, String entityCode, String referenceNo,
                        Map<String, Object> data, UUID createdBy, Instant createdAt, Instant updatedAt) {
    public RecordRow {
        data = data == null ? Map.of() : data;
    }

    public RecordRow withData(Map<String, Object> newData, Instant at) {
        return new RecordRow(id, appCode, defVersion, entityCode, referenceNo, newData, createdBy, createdAt, at);
    }
}
