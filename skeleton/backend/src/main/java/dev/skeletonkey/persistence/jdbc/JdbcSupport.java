package dev.skeletonkey.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/** Small helpers shared by the JDBC adapters: timestamptz and jsonb conversion. */
final class JdbcSupport {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private JdbcSupport() {}

    static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    static UUID uuid(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, UUID.class);
    }

    static String json(ObjectMapper om, Object value) {
        if (value == null) {
            return null;
        }
        try {
            return om.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize JSON", e);
        }
    }

    static Map<String, Object> map(ObjectMapper om, String json) {
        try {
            return json == null ? Map.of() : om.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored JSON is unreadable", e);
        }
    }

    static JsonNode node(ObjectMapper om, String json) {
        try {
            return json == null ? null : om.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored JSON is unreadable", e);
        }
    }
}
