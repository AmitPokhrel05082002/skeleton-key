package dev.skeletonkey.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.domain.model.HistoryEntry;
import dev.skeletonkey.persistence.HistoryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Append and find only. The table also has a trigger rejecting UPDATE and DELETE. */
@Repository
public class JdbcHistoryRepository implements HistoryRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper om;

    public JdbcHistoryRepository(JdbcClient jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @Override
    public void append(UUID instanceId, HistoryEntry e) {
        jdbc.sql("""
                INSERT INTO history_entry (instance_id, event_time, kind, actor_id, from_step, to_step, action_code,
                    from_status, to_status, time_in_step_ms, remarks, attachment, details)
                VALUES (:instance, :time, :kind, :actor, :fromStep, :toStep, :action, :fromStatus, :toStatus,
                    :ms, :remarks, :attachment, cast(:details as jsonb))
                """)
                .param("instance", instanceId)
                .param("time", JdbcSupport.ts(e.eventTime()))
                .param("kind", e.kind())
                .param("actor", e.actorId())
                .param("fromStep", e.fromStep())
                .param("toStep", e.toStep())
                .param("action", e.actionCode())
                .param("fromStatus", e.fromStatus())
                .param("toStatus", e.toStatus())
                .param("ms", e.timeInStepMs())
                .param("remarks", e.remarks())
                .param("attachment", e.attachmentRef())
                .param("details", JdbcSupport.json(om, e.detail()))
                .update();
    }

    @Override
    public List<HistoryEntry> findByInstance(UUID instanceId) {
        return jdbc.sql("SELECT kind, action_code, from_step, to_step, from_status, to_status, actor_id, remarks, "
                        + "attachment, details::text AS details, event_time, time_in_step_ms "
                        + "FROM history_entry WHERE instance_id = :id ORDER BY event_time, seq")
                .param("id", instanceId)
                .query((rs, n) -> new HistoryEntry(
                        rs.getString("kind"), rs.getString("action_code"), rs.getString("from_step"),
                        rs.getString("to_step"), rs.getString("from_status"), rs.getString("to_status"),
                        JdbcSupport.uuid(rs, "actor_id"), rs.getString("remarks"), rs.getString("attachment"),
                        JdbcSupport.node(om, rs.getString("details")), JdbcSupport.instant(rs, "event_time"),
                        rs.getLong("time_in_step_ms")))
                .list();
    }
}
