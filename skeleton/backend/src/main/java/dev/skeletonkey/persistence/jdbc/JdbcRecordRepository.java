package dev.skeletonkey.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.persistence.RecordRepository;
import dev.skeletonkey.persistence.RecordRow;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRecordRepository implements RecordRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper om;

    public JdbcRecordRepository(JdbcClient jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @Override
    public void insert(RecordRow r) {
        jdbc.sql("""
                INSERT INTO record (id, app_code, version, entity_code, reference_no, data, created_by, created_at, updated_at)
                VALUES (:id, :app, :version, :entity, :ref, cast(:data as jsonb), :createdBy, :createdAt, :updatedAt)
                """)
                .param("id", r.id())
                .param("app", r.appCode())
                .param("version", r.defVersion())
                .param("entity", r.entityCode())
                .param("ref", r.referenceNo())
                .param("data", JdbcSupport.json(om, r.data()))
                .param("createdBy", r.createdBy())
                .param("createdAt", JdbcSupport.ts(r.createdAt()))
                .param("updatedAt", JdbcSupport.ts(r.updatedAt()))
                .update();
    }

    @Override
    public Optional<RecordRow> findById(UUID id) {
        return jdbc.sql("SELECT id, app_code, version, entity_code, reference_no, data::text AS data, created_by, "
                        + "created_at, updated_at FROM record WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new RecordRow(
                        JdbcSupport.uuid(rs, "id"), rs.getString("app_code"), rs.getInt("version"),
                        rs.getString("entity_code"), rs.getString("reference_no"),
                        JdbcSupport.map(om, rs.getString("data")), JdbcSupport.uuid(rs, "created_by"),
                        JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at")))
                .optional();
    }

    @Override
    public boolean updateData(UUID id, Map<String, Object> data, Instant updatedAt) {
        return jdbc.sql("UPDATE record SET data = cast(:data as jsonb), updated_at = :at WHERE id = :id")
                .param("data", JdbcSupport.json(om, data))
                .param("at", JdbcSupport.ts(updatedAt))
                .param("id", id)
                .update() > 0;
    }
}
