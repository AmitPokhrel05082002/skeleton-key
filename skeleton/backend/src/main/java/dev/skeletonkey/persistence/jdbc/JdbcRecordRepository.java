package dev.skeletonkey.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.persistence.RecordRepository;
import dev.skeletonkey.persistence.RecordRow;
import dev.skeletonkey.domain.access.RowVisibility;
import dev.skeletonkey.persistence.RecordQuery;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
    public Page search(RecordQuery q) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("app", q.appCode());
        params.put("entity", q.entityCode());
        StringBuilder where = new StringBuilder("r.app_code = :app AND r.entity_code = :entity");
        int i = 0;
        for (Map.Entry<String, String> f : q.filters().entrySet()) {
            where.append(" AND r.data ->> :fk").append(i).append(" = :fv").append(i);
            params.put("fk" + i, f.getKey());
            params.put("fv" + i, f.getValue());
            i++;
        }
        RowVisibility.Sql vis = q.visibility().sql("r", "wi", "rv");
        where.append(" AND ").append(vis.text());
        params.putAll(vis.params());
        if (q.mine()) {
            where.append(" AND r.created_by = :scopeUser");
            params.put("scopeUser", q.visibility().userId());
        }
        if (q.assignedToMe()) {
            where.append(" AND wi.assignee = :scopeUser");
            params.put("scopeUser", q.visibility().userId());
        }
        if (q.queueOnly()) {
            RowVisibility.Sql queue = q.visibility().queueSql("wi", "rq");
            where.append(" AND ").append(queue.text());
            params.putAll(queue.params());
        }
        String from = " FROM record r LEFT JOIN workflow_instance wi ON wi.record_id = r.id WHERE " + where;
        long total = jdbc.sql("SELECT count(*)" + from).params(params).query(Long.class).single();
        if (total == 0) {
            return new Page(List.of(), 0);
        }
        Map<String, Object> pageParams = new LinkedHashMap<>(params);
        pageParams.put("limit", q.size());
        pageParams.put("offset", (long) (q.page() - 1) * q.size());
        String order = orderBy(q.sort(), pageParams);
        List<RecordRow> rows = jdbc.sql("SELECT r.id, r.app_code, r.version, r.entity_code, r.reference_no, "
                        + "r.data::text AS data, r.created_by, r.created_at, r.updated_at" + from
                        + " ORDER BY " + order + " LIMIT :limit OFFSET :offset")
                .params(pageParams)
                .query((rs, n) -> new RecordRow(
                        JdbcSupport.uuid(rs, "id"), rs.getString("app_code"), rs.getInt("version"),
                        rs.getString("entity_code"), rs.getString("reference_no"),
                        JdbcSupport.map(om, rs.getString("data")), JdbcSupport.uuid(rs, "created_by"),
                        JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at")))
                .list();
        return new Page(rows, total);
    }

    /** ORDER BY text built only from fixed fragments; the data field code is a bound parameter. */
    private static String orderBy(RecordQuery.Sort s, Map<String, Object> params) {
        String dir = s.descending() ? " DESC" : " ASC";
        String expr;
        switch (s.kind()) {
            case FIELD -> {
                params.put("sortField", s.field());
                expr = s.numeric() ? "cast(r.data ->> :sortField as numeric)" : "(r.data ->> :sortField)";
            }
            case REFERENCE -> expr = "r.reference_no";
            case CREATED_AT -> expr = "r.created_at";
            case UPDATED_AT -> expr = "r.updated_at";
            case CREATED_BY -> expr = "r.created_by";
            case STATUS -> expr = "wi.status";
            case STEP -> expr = "wi.current_step";
            case ASSIGNEE -> expr = "wi.assignee";
            case DUE_AT -> expr = "wi.due_at";
            default -> throw new IllegalStateException("Unhandled sort " + s.kind());
        }
        return expr + dir + " NULLS LAST, r.id ASC";
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
