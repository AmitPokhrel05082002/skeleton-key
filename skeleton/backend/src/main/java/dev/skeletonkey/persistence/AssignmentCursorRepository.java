package dev.skeletonkey.persistence;

import dev.skeletonkey.domain.route.CursorUpdate;
import java.util.Optional;
import java.util.UUID;

/** Port for round-robin cursors, keyed by (app, workflow, step). */
public interface AssignmentCursorRepository {
    Optional<UUID> findLastUser(String appCode, String workflowCode, String stepCode);

    /** Upserts the cursor. Call only with the outcome of a successful action. */
    void apply(CursorUpdate update);
}
