package dev.skeletonkey.record;

import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Status;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Workflow;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Workflow facts shown next to a record. Pure function of the instance, its pinned workflow and a name map. */
public record WorkflowSummary(UUID instanceId, String step, String stepLabel, String status, String statusColor,
                              UUID assignee, String assigneeName, Instant dueAt, boolean ended) {
    public static final WorkflowSummary NONE =
            new WorkflowSummary(null, null, null, null, null, null, null, null, false);

    /**
     * @param instance null for a record without an instance
     * @param pinned   the workflow of the version the instance is pinned to; may be null when it cannot be loaded
     * @param names    display names by user id
     */
    public static WorkflowSummary of(InstanceState instance, Workflow pinned, Map<UUID, String> names) {
        if (instance == null) {
            return NONE;
        }
        String label = pinned == null ? null : pinned.step(instance.step()).map(Step::label).orElse(null);
        String stepLabel = label == null || label.isBlank() ? instance.step() : label;
        String color = pinned == null ? null : pinned.statuses().stream()
                .filter(s -> s.label().equals(instance.status())).map(Status::color)
                .filter(c -> c != null).findFirst().orElse(null);
        if (instance.ended()) {
            return new WorkflowSummary(instance.id(), instance.step(), stepLabel, instance.status(), color,
                    null, null, null, true);
        }
        UUID assignee = instance.assigneeId();
        String name = assignee == null || names == null ? null : names.get(assignee);
        return new WorkflowSummary(instance.id(), instance.step(), stepLabel, instance.status(), color,
                assignee, name, instance.dueAt(), false);
    }
}
