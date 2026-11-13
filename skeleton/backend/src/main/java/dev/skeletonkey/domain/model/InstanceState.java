package dev.skeletonkey.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Runtime state of a workflow instance. Status is a plain label, never an enum.
 *
 * <p>{@code startedBy} (column {@code started_by}) is the user id of the starting principal, null for a public
 * start; the INITIATOR assignee strategy assigns it. {@code startedAt} (column {@code created_at}) is the instant
 * {@code Router.start} ran and is the value of {@code instance.createdAt} in expressions. Both are set once and
 * never change.
 */
public record InstanceState(UUID id, UUID recordId, String appCode, int defVersion, String workflowCode,
                            String step, String status, UUID assigneeId, Instant dueAt, boolean ended,
                            Instant stepEnteredAt, long lockVersion, UUID startedBy, Instant startedAt) {

    /** Copy with the immutable start facts set. */
    public InstanceState withStart(UUID startedBy, Instant startedAt) {
        return new InstanceState(id, recordId, appCode, defVersion, workflowCode, step, status, assigneeId, dueAt,
                ended, stepEnteredAt, lockVersion, startedBy, startedAt);
    }

    /** Copy with a different lock version. */
    public InstanceState withLockVersion(long lockVersion) {
        return new InstanceState(id, recordId, appCode, defVersion, workflowCode, step, status, assigneeId, dueAt,
                ended, stepEnteredAt, lockVersion, startedBy, startedAt);
    }
}
