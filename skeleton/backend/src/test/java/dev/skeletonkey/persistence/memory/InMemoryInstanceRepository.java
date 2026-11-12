package dev.skeletonkey.persistence.memory;

import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.persistence.InstanceRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;

public class InMemoryInstanceRepository implements InstanceRepository {
    private final Map<UUID, InstanceState> rows = new HashMap<>();

    @Override
    public synchronized void insert(InstanceState state, UUID startedBy) {
        if (rows.containsKey(state.id())
                || rows.values().stream().anyMatch(s -> s.recordId().equals(state.recordId()))) {
            throw new IllegalStateException("Duplicate instance for " + state.id());
        }
        rows.put(state.id(), state);
    }

    @Override
    public synchronized Optional<InstanceState> findById(UUID id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public synchronized Optional<InstanceState> findByRecordId(UUID recordId) {
        return rows.values().stream().filter(s -> s.recordId().equals(recordId)).findFirst();
    }

    @Override
    public synchronized InstanceState update(InstanceState s) {
        InstanceState stored = rows.get(s.id());
        if (stored == null || stored.lockVersion() != s.lockVersion()) {
            throw new OptimisticLockingFailureException("Instance " + s.id() + " was modified concurrently");
        }
        InstanceState saved = new InstanceState(s.id(), s.recordId(), s.appCode(), s.defVersion(),
                s.workflowCode(), s.step(), s.status(), s.assigneeId(), s.dueAt(), s.ended(), s.stepEnteredAt(),
                s.lockVersion() + 1);
        rows.put(s.id(), saved);
        return saved;
    }

    @Override
    public synchronized Map<UUID, Long> openTaskCounts(Collection<UUID> userIds) {
        Map<UUID, Long> result = new HashMap<>();
        for (UUID id : userIds) {
            result.put(id, rows.values().stream()
                    .filter(s -> !s.ended() && id.equals(s.assigneeId())).count());
        }
        return result;
    }
}
