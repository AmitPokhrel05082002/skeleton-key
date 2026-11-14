package dev.skeletonkey.persistence.jdbc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.persistence.DefinitionRepository;
import dev.skeletonkey.persistence.DefinitionVersionInfo;
import dev.skeletonkey.persistence.DefinitionVersionRow;
import dev.skeletonkey.persistence.DuplicateVersionException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDefinitionRepository implements DefinitionRepository {
    private static final String COLUMNS =
            "id, app_code, version, status, content_hash, document::text AS document, created_at, published_at";

    /** Reads documents with exact decimals so numeric values round-trip (Req 1.7). */
    private final ObjectMapper reader = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final JdbcClient jdbc;

    public JdbcDefinitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int maxVersion(String appCode) {
        // max() is SQL NULL on an empty set, which JdbcClient.single() rejects, so coalesce in SQL.
        return jdbc.sql("SELECT coalesce(max(version), 0) FROM definition_version WHERE app_code = :app")
                .param("app", appCode).query(Integer.class).single();
    }

    @Override
    public void insert(DefinitionVersionRow r) {
        try {
            jdbc.sql("""
                    INSERT INTO definition_version (id, app_code, version, status, content_hash, document, created_at, published_at)
                    VALUES (:id, :app, :version, :status, :hash, cast(:doc as jsonb), :created, :published)
                    """)
                    .param("id", r.id())
                    .param("app", r.appCode())
                    .param("version", r.version())
                    .param("status", r.status())
                    .param("hash", r.contentHash())
                    .param("doc", r.document().toString())
                    .param("created", JdbcSupport.ts(r.createdAt()))
                    .param("published", JdbcSupport.ts(r.publishedAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateVersionException(r.appCode(), r.version());
        }
    }

    @Override
    public Optional<DefinitionVersionRow> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM definition_version WHERE id = :id")
                .param("id", id).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<DefinitionVersionRow> findByAppAndVersion(String appCode, int version) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM definition_version WHERE app_code = :app AND version = :v")
                .param("app", appCode).param("v", version).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<DefinitionVersionRow> findLatestPublished(String appCode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM definition_version "
                        + "WHERE app_code = :app AND status = 'PUBLISHED' ORDER BY version DESC LIMIT 1")
                .param("app", appCode).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public List<String> findAppCodes() {
        return jdbc.sql("SELECT app_code FROM (SELECT DISTINCT app_code FROM definition_version) d ORDER BY app_code COLLATE \"C\"")
                .query(String.class).list();
    }

    @Override
    public List<DefinitionVersionInfo> findInfoByApp(String appCode) {
        return jdbc.sql("SELECT id, app_code, version, status, content_hash, created_at, published_at "
                        + "FROM definition_version WHERE app_code = :app ORDER BY version DESC")
                .param("app", appCode)
                .query((rs, n) -> new DefinitionVersionInfo(JdbcSupport.uuid(rs, "id"), rs.getString("app_code"),
                        rs.getInt("version"), rs.getString("status"), rs.getString("content_hash"),
                        JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "published_at")))
                .list();
    }

    @Override
    public boolean existsByContentHash(String contentHash) {
        return jdbc.sql("SELECT count(*) FROM definition_version WHERE content_hash = :h")
                .param("h", contentHash).query(Long.class).single() > 0;
    }

    @Override
    public boolean markPublished(UUID id, Instant publishedAt) {
        return jdbc.sql("UPDATE definition_version SET status = 'PUBLISHED', published_at = :at "
                        + "WHERE id = :id AND status = 'DRAFT'")
                .param("at", JdbcSupport.ts(publishedAt)).param("id", id).update() == 1;
    }

    private DefinitionVersionRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new DefinitionVersionRow(JdbcSupport.uuid(rs, "id"), rs.getString("app_code"), rs.getInt("version"),
                    rs.getString("status"), rs.getString("content_hash"), reader.readTree(rs.getString("document")),
                    JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "published_at"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Stored definition is unreadable", e);
        }
    }
}
