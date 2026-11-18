package dev.skeletonkey.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only queries for public status tracking. Nothing here returns the contact value itself. */
public interface TrackingRepository {

    /** A record with that exact reference in that app: id and entity code only. */
    Optional<Candidate> findCandidate(String appCode, String referenceNo);

    /** True when the record's {@code contactField} value, trimmed and lower-cased, equals {@code emailLower}. */
    boolean contactMatches(UUID recordId, String contactField, String emailLower);

    /** Public facts about a matched record and its workflow instance; empty when there is no instance. */
    Optional<Snapshot> snapshot(UUID recordId, String titleField);

    /** Status changes: the START row and every movement row (ACTION, SKIPPED, AUTO) that changed the status. */
    List<TimelineRow> timeline(UUID instanceId);

    record Candidate(UUID recordId, String entityCode) {}

    record Snapshot(String referenceNo, String title, Instant createdAt, UUID instanceId, int version,
                    String workflowCode, String status, Instant updatedAt) {}

    record TimelineRow(Instant at, String status) {}
}
