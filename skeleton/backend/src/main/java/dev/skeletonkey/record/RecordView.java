package dev.skeletonkey.record;

import dev.skeletonkey.persistence.RecordRow;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** API representation of a stored record. */
public record RecordView(UUID id, String referenceNo, String appCode, int defVersion, String entityCode,
                         Map<String, Object> data, UUID createdBy, Instant createdAt, Instant updatedAt) {

    public static RecordView of(RecordRow r) {
        return new RecordView(r.id(), r.referenceNo(), r.appCode(), r.defVersion(), r.entityCode(), r.data(),
                r.createdBy(), r.createdAt(), r.updatedAt());
    }
}
