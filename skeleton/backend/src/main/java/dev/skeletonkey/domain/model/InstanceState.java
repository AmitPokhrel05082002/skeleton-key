package dev.skeletonkey.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Runtime state of a workflow instance. Status is a plain label, never an enum. */
public record InstanceState(UUID id, UUID recordId, String appCode, int defVersion, String workflowCode,
                            String step, String status, UUID assigneeId, Instant dueAt, boolean ended,
                            Instant stepEnteredAt, long lockVersion) {}
