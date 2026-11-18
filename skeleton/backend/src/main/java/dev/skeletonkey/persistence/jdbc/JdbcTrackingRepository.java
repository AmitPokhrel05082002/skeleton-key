package dev.skeletonkey.persistence.jdbc;

import dev.skeletonkey.persistence.TrackingRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTrackingRepository implements TrackingRepository {

    private final JdbcClient jdbc;

    public JdbcTrackingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Candidate> findCandidate(String appCode, String referenceNo) {
        return jdbc.sql("SELECT id, entity_code FROM record WHERE app_code = :app AND reference_no = :ref")
                .param("app", appCode).param("ref", referenceNo)
                .query((rs, n) -> new Candidate(JdbcSupport.uuid(rs, "id"), rs.getString("entity_code")))
                .optional();
    }

    @Override
    public boolean contactMatches(UUID recordId, String contactField, String emailLower) {
        // Equality, not LIKE: wildcard characters in the input have no special meaning. The field name is a bound
        // parameter of ->>, never part of the SQL text.
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM record WHERE id = :id "
                        + "AND lower(btrim(data ->> CAST(:field AS text), E' \\t\\r\\n')) = :email)")
                .param("id", recordId).param("field", contactField).param("email", emailLower)
                .query(Boolean.class).single());
    }

    @Override
    public Optional<Snapshot> snapshot(UUID recordId, String titleField) {
        return jdbc.sql("""
                SELECT r.reference_no, r.data ->> CAST(:title AS text) AS title, r.created_at,
                       i.id AS instance_id, i.version, i.workflow_code, i.status, i.updated_at
                FROM record r JOIN workflow_instance i ON i.record_id = r.id
                WHERE r.id = :id
                """)
                .param("id", recordId).param("title", titleField == null ? "" : titleField)
                .query((rs, n) -> new Snapshot(rs.getString("reference_no"),
                        titleField == null ? null : rs.getString("title"), JdbcSupport.instant(rs, "created_at"),
                        JdbcSupport.uuid(rs, "instance_id"), rs.getInt("version"), rs.getString("workflow_code"),
                        rs.getString("status"), JdbcSupport.instant(rs, "updated_at")))
                .optional();
    }

    @Override
    public List<TimelineRow> timeline(UUID instanceId) {
        return jdbc.sql("""
                SELECT event_time, to_status FROM history_entry
                WHERE instance_id = :id AND to_status IS NOT NULL
                  AND (kind = 'START'
                       OR (kind IN ('ACTION', 'SKIPPED', 'AUTO') AND to_status IS DISTINCT FROM from_status))
                ORDER BY event_time, seq
                """)
                .param("id", instanceId)
                .query((rs, n) -> new TimelineRow(JdbcSupport.instant(rs, "event_time"), rs.getString("to_status")))
                .list();
    }
}
