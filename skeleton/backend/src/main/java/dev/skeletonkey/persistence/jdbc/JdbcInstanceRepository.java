package dev.skeletonkey.persistence.jdbc;

import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.persistence.InstanceRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInstanceRepository implements InstanceRepository {
    private static final String COLUMNS = "id, record_id, app_code, version, workflow_code, current_step, status, "
            + "ended, assignee, due_at, step_entered_at, lock_version, started_by, created_at";

    private static final RowMapper<InstanceState> MAPPER = (rs, n) -> new InstanceState(
            JdbcSupport.uuid(rs, "id"), JdbcSupport.uuid(rs, "record_id"), rs.getString("app_code"),
            rs.getInt("version"), rs.getString("workflow_code"), rs.getString("current_step"),
            rs.getString("status"), JdbcSupport.uuid(rs, "assignee"), JdbcSupport.instant(rs, "due_at"),
            rs.getBoolean("ended"), JdbcSupport.instant(rs, "step_entered_at"), rs.getLong("lock_version"),
            JdbcSupport.uuid(rs, "started_by"), JdbcSupport.instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public JdbcInstanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(InstanceState s) {
        jdbc.sql("""
                INSERT INTO workflow_instance (id, record_id, app_code, version, workflow_code, current_step, status,
                    ended, assignee, due_at, step_entered_at, started_by, created_at, lock_version)
                VALUES (:id, :recordId, :app, :version, :wf, :step, :status, :ended, :assignee, :dueAt,
                    :enteredAt, :startedBy, :createdAt, :lock)
                """)
                .param("id", s.id())
                .param("recordId", s.recordId())
                .param("app", s.appCode())
                .param("version", s.defVersion())
                .param("wf", s.workflowCode())
                .param("step", s.step())
                .param("status", s.status())
                .param("ended", s.ended())
                .param("assignee", s.assigneeId())
                .param("dueAt", JdbcSupport.ts(s.dueAt()))
                .param("enteredAt", JdbcSupport.ts(s.stepEnteredAt()))
                .param("startedBy", s.startedBy())
                .param("createdAt", JdbcSupport.ts(s.startedAt()))
                .param("lock", s.lockVersion())
                .update();
    }

    @Override
    public Optional<InstanceState> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM workflow_instance WHERE id = :id")
                .param("id", id).query(MAPPER).optional();
    }

    @Override
    public Optional<InstanceState> findByRecordId(UUID recordId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM workflow_instance WHERE record_id = :rid")
                .param("rid", recordId).query(MAPPER).optional();
    }

    @Override
    public InstanceState update(InstanceState s) {
        int rows = jdbc.sql("""
                UPDATE workflow_instance SET current_step = :step, status = :status, ended = :ended,
                    assignee = :assignee, due_at = :dueAt, step_entered_at = :enteredAt, updated_at = :now,
                    lock_version = lock_version + 1
                WHERE id = :id AND lock_version = :lock
                """)
                .param("step", s.step())
                .param("status", s.status())
                .param("ended", s.ended())
                .param("assignee", s.assigneeId())
                .param("dueAt", JdbcSupport.ts(s.dueAt()))
                .param("enteredAt", JdbcSupport.ts(s.stepEnteredAt()))
                .param("now", JdbcSupport.ts(Instant.now()))
                .param("id", s.id())
                .param("lock", s.lockVersion())
                .update();
        if (rows == 0) {
            throw new OptimisticLockingFailureException("Instance " + s.id() + " was modified concurrently");
        }
        return s.withLockVersion(s.lockVersion() + 1);
    }

    @Override
    public Map<UUID, Long> openTaskCounts(Collection<UUID> userIds) {
        Map<UUID, Long> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        for (UUID id : userIds) {
            result.put(id, 0L);
        }
        jdbc.sql("SELECT assignee, count(*) AS n FROM workflow_instance "
                        + "WHERE ended = false AND assignee IN (:ids) GROUP BY assignee")
                .param("ids", userIds)
                .query((rs, i) -> {
                    result.put(JdbcSupport.uuid(rs, "assignee"), rs.getLong("n"));
                    return null;
                }).list();
        return result;
    }
}
