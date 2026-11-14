package dev.skeletonkey.record;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A record as the API returns it: the {@link RecordView} properties followed by the workflow summary, flat. */
public record RecordItem(UUID id, String referenceNo, String appCode, int defVersion, String entityCode,
                         Map<String, Object> data, UUID createdBy, Instant createdAt, Instant updatedAt,
                         UUID instanceId, String step, String stepLabel, String status, String statusColor,
                         UUID assignee, String assigneeName, Instant dueAt, boolean ended) {

    public static RecordItem of(RecordView v, WorkflowSummary s) {
        return new RecordItem(v.id(), v.referenceNo(), v.appCode(), v.defVersion(), v.entityCode(), v.data(),
                v.createdBy(), v.createdAt(), v.updatedAt(), s.instanceId(), s.step(), s.stepLabel(), s.status(),
                s.statusColor(), s.assignee(), s.assigneeName(), s.dueAt(), s.ended());
    }
}
