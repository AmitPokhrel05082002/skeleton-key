package dev.skeletonkey.router;

import dev.skeletonkey.domain.model.Assignee;
import dev.skeletonkey.domain.route.CursorUpdate;
import dev.skeletonkey.persistence.AssignmentCursorRepository;
import dev.skeletonkey.persistence.InstanceRepository;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.UserRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Picks the assignee for a step entry. Read-only: a chosen round-robin cursor is returned, never stored. */
public class AssigneeResolver {

    /** Result of a resolution; empty assignee means the task stays unassigned (role queue). */
    public record Resolution(Optional<UUID> assignee, Optional<CursorUpdate> cursorUpdate) {
        static final Resolution UNASSIGNED = new Resolution(Optional.empty(), Optional.empty());

        static Resolution of(UUID id) {
            return new Resolution(Optional.of(id), Optional.empty());
        }
    }

    private final UserRepository users;
    private final InstanceRepository instances;
    private final AssignmentCursorRepository cursors;

    public AssigneeResolver(UserRepository users, InstanceRepository instances, AssignmentCursorRepository cursors) {
        this.users = users;
        this.instances = instances;
        this.cursors = cursors;
    }

    /**
     * @param initiator the principal that started the instance (may be null)
     * @param data      the record data, read for FROM_FIELD
     */
    public Resolution resolve(Assignee assignee, String appCode, String workflowCode, String stepCode,
                              UUID initiator, Map<String, ?> data) {
        if (assignee == null) {
            return Resolution.UNASSIGNED;
        }
        return switch (assignee.strategy()) {
            case ROLE_QUEUE -> Resolution.UNASSIGNED;
            case LEAST_LOADED -> leastLoaded(assignee.role());
            case ROUND_ROBIN -> roundRobin(assignee.role(), appCode, workflowCode, stepCode);
            case INITIATOR -> initiator == null ? Resolution.UNASSIGNED : Resolution.of(initiator);
            case FROM_FIELD -> fromField(assignee.field(), data);
        };
    }

    private Resolution leastLoaded(String role) {
        List<UUID> ids = users.findActiveByRole(role).stream().map(UserAccount::id).toList();
        if (ids.isEmpty()) {
            return Resolution.UNASSIGNED;
        }
        Map<UUID, Long> counts = instances.openTaskCounts(ids);
        return ids.stream()
                .min(Comparator.<UUID>comparingLong(id -> counts.getOrDefault(id, 0L)).thenComparing(Comparator.naturalOrder()))
                .map(Resolution::of)
                .orElse(Resolution.UNASSIGNED);
    }

    private Resolution roundRobin(String role, String appCode, String workflowCode, String stepCode) {
        List<UUID> ids = users.findActiveByRole(role).stream().map(UserAccount::id).sorted().toList();
        if (ids.isEmpty()) {
            return Resolution.UNASSIGNED;
        }
        Optional<UUID> last = cursors.findLastUser(appCode, workflowCode, stepCode);
        UUID chosen = last.flatMap(l -> ids.stream().filter(id -> id.compareTo(l) > 0).findFirst())
                .orElse(ids.get(0));
        return new Resolution(Optional.of(chosen),
                Optional.of(new CursorUpdate(appCode, workflowCode, stepCode, chosen)));
    }

    private Resolution fromField(String field, Map<String, ?> data) {
        Object value = field == null || data == null ? null : data.get(field);
        if (value == null) {
            return Resolution.UNASSIGNED;
        }
        try {
            UUID id = UUID.fromString(value.toString());
            return users.findById(id).filter(UserAccount::active).map(u -> Resolution.of(u.id()))
                    .orElse(Resolution.UNASSIGNED);
        } catch (IllegalArgumentException e) {
            return Resolution.UNASSIGNED;
        }
    }
}
