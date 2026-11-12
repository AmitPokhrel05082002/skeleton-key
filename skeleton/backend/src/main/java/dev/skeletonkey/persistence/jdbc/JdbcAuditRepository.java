package dev.skeletonkey.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.persistence.AuditEntry;
import dev.skeletonkey.persistence.AuditRepository;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditRepository implements AuditRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper om;

    public JdbcAuditRepository(JdbcClient jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @Override
    public void append(AuditEntry e) {
        jdbc.sql("""
                INSERT INTO audit_log (event_time, actor_id, action, target_type, target_id, details)
                VALUES (:time, :actor, :action, :type, :target, cast(:details as jsonb))
                """)
                .param("time", JdbcSupport.ts(e.eventTime()))
                .param("actor", e.actorId())
                .param("action", e.action())
                .param("type", e.targetType())
                .param("target", e.targetId())
                .param("details", JdbcSupport.json(om, e.details()))
                .update();
    }

    @Override
    public List<AuditEntry> findByTarget(String targetType, String targetId) {
        return jdbc.sql("SELECT event_time, actor_id, action, target_type, target_id, details::text AS details "
                        + "FROM audit_log WHERE target_type = :type AND target_id = :target ORDER BY event_time, id")
                .param("type", targetType)
                .param("target", targetId)
                .query((rs, n) -> new AuditEntry(JdbcSupport.instant(rs, "event_time"),
                        JdbcSupport.uuid(rs, "actor_id"), rs.getString("action"), rs.getString("target_type"),
                        rs.getString("target_id"), JdbcSupport.node(om, rs.getString("details"))))
                .list();
    }
}
