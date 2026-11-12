package dev.skeletonkey.persistence.memory;

import dev.skeletonkey.domain.route.CursorUpdate;
import dev.skeletonkey.persistence.AssignmentCursorRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryAssignmentCursorRepository implements AssignmentCursorRepository {
    private final Map<String, UUID> cursors = new ConcurrentHashMap<>();

    private static String key(String app, String wf, String step) {
        return app + "|" + wf + "|" + step;
    }

    @Override
    public Optional<UUID> findLastUser(String appCode, String workflowCode, String stepCode) {
        return Optional.ofNullable(cursors.get(key(appCode, workflowCode, stepCode)));
    }

    @Override
    public void apply(CursorUpdate u) {
        if (u.lastUserId() == null) {
            cursors.remove(key(u.appCode(), u.workflowCode(), u.stepCode()));
        } else {
            cursors.put(key(u.appCode(), u.workflowCode(), u.stepCode()), u.lastUserId());
        }
    }
}
