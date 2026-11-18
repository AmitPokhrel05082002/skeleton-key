package dev.skeletonkey.persistence.jdbc;

import dev.skeletonkey.persistence.PageResult;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.UserRepository;
import dev.skeletonkey.persistence.UserRow;
import dev.skeletonkey.persistence.UserSearch;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcUserRepository implements UserRepository {
    private static final String SELECT =
            "SELECT id, username, password_hash, display_name, active FROM app_user ";

    private final JdbcClient jdbc;

    public JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UserAccount u) {
        jdbc.sql("INSERT INTO app_user (id, username, password_hash, display_name, active) "
                        + "VALUES (:id, :username, :hash, :display, :active)")
                .param("id", u.id())
                .param("username", u.username())
                .param("hash", u.passwordHash())
                .param("display", u.displayName())
                .param("active", u.active())
                .update();
        for (String role : u.roles()) {
            jdbc.sql("INSERT INTO user_role (user_id, role_code) VALUES (:id, :role)")
                    .param("id", u.id()).param("role", role).update();
        }
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
        return jdbc.sql(SELECT + "WHERE id = :id").param("id", id)
                .query((rs, n) -> map(rs)).optional().map(this::withRoles);
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.sql(SELECT + "WHERE lower(username) = lower(:u)").param("u", username)
                .query((rs, n) -> map(rs)).optional().map(this::withRoles);
    }

    @Override
    public boolean isActive(UUID id) {
        return jdbc.sql("SELECT active FROM app_user WHERE id = :id").param("id", id)
                .query(Boolean.class).optional().orElse(false);
    }

    @Override
    public List<UserAccount> findActiveByRole(String roleCode) {
        return jdbc.sql(SELECT + "WHERE active = true AND id IN "
                        + "(SELECT user_id FROM user_role WHERE role_code = :role) ORDER BY id")
                .param("role", roleCode)
                .query((rs, n) -> map(rs)).list().stream().map(this::withRoles).toList();
    }

    @Override
    public Map<UUID, String> findDisplayNames(Collection<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, display_name FROM app_user WHERE id IN (:ids)")
                .param("ids", userIds)
                .query((rs, n) -> {
                    names.put(JdbcSupport.uuid(rs, "id"), rs.getString("display_name"));
                    return null;
                }).list();
        return names;
    }

    @Override
    public PageResult<UserRow> search(UserSearch s) {
        // Only the filters that are present are added, so no untyped NULL parameter reaches PostgreSQL.
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (s.q() != null && !s.q().isBlank()) {
            where.append(" AND (u.username ILIKE :q ESCAPE '\\' OR u.display_name ILIKE :q ESCAPE '\\')");
            params.put("q", UserSearch.likePattern(s.q().trim()));
        }
        if (s.role() != null && !s.role().isBlank()) {
            where.append(" AND EXISTS (SELECT 1 FROM user_role r WHERE r.user_id = u.id AND r.role_code = :role)");
            params.put("role", s.role());
        }
        if (s.active() != null) {
            where.append(" AND u.active = :active");
            params.put("active", s.active());
        }
        long total = jdbc.sql("SELECT count(*) FROM app_user u" + where).params(params)
                .query(Long.class).single();
        String order = s.sortField().sql() + (s.asc() ? " ASC" : " DESC") + ", u.id";
        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("size", s.size());
        pageParams.put("offset", (long) s.page() * s.size());
        List<UserRow> rows = jdbc.sql("SELECT u.id, u.username, u.display_name, u.active, u.created_at "
                        + "FROM app_user u" + where + " ORDER BY " + order + " LIMIT :size OFFSET :offset")
                .params(pageParams)
                .query((rs, n) -> new UserRow(JdbcSupport.uuid(rs, "id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getBoolean("active"), Set.of(),
                        JdbcSupport.instant(rs, "created_at")))
                .list();
        Map<UUID, Set<String>> roles = rolesOf(rows.stream().map(UserRow::id).toList());
        List<UserRow> out = new ArrayList<>();
        for (UserRow r : rows) {
            out.add(new UserRow(r.id(), r.username(), r.displayName(), r.active(),
                    roles.getOrDefault(r.id(), Set.of()), r.createdAt()));
        }
        return new PageResult<>(out, s.page(), s.size(), total);
    }

    @Override
    public Optional<UserRow> findRow(UUID id) {
        return jdbc.sql("SELECT id, username, display_name, active, created_at FROM app_user WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new UserRow(JdbcSupport.uuid(rs, "id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getBoolean("active"), Set.of(),
                        JdbcSupport.instant(rs, "created_at")))
                .optional()
                .map(r -> new UserRow(r.id(), r.username(), r.displayName(), r.active(),
                        rolesOf(List.of(r.id())).getOrDefault(r.id(), Set.of()), r.createdAt()));
    }

    @Override
    @Transactional
    public void update(UUID id, String displayName, Boolean active, Set<String> roles) {
        if (displayName != null) {
            jdbc.sql("UPDATE app_user SET display_name = :d WHERE id = :id")
                    .param("d", displayName).param("id", id).update();
        }
        if (active != null) {
            jdbc.sql("UPDATE app_user SET active = :a WHERE id = :id")
                    .param("a", active).param("id", id).update();
        }
        if (roles != null) {
            jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", id).update();
            for (String role : roles) {
                jdbc.sql("INSERT INTO user_role (user_id, role_code) VALUES (:id, :role)")
                        .param("id", id).param("role", role).update();
            }
        }
    }

    @Override
    public void updatePasswordHash(UUID id, String passwordHash) {
        jdbc.sql("UPDATE app_user SET password_hash = :h WHERE id = :id")
                .param("h", passwordHash).param("id", id).update();
    }

    private Map<UUID, Set<String>> rolesOf(Collection<UUID> ids) {
        Map<UUID, Set<String>> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT user_id, role_code FROM user_role WHERE user_id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> {
                    out.computeIfAbsent(JdbcSupport.uuid(rs, "user_id"), k -> new HashSet<>())
                            .add(rs.getString("role_code"));
                    return null;
                }).list();
        return out;
    }

    private UserAccount map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserAccount(JdbcSupport.uuid(rs, "id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("display_name"), rs.getBoolean("active"), Set.of());
    }

    private UserAccount withRoles(UserAccount u) {
        Set<String> roles = new HashSet<>(jdbc.sql("SELECT role_code FROM user_role WHERE user_id = :id")
                .param("id", u.id()).query(String.class).list());
        return new UserAccount(u.id(), u.username(), u.passwordHash(), u.displayName(), u.active(), roles);
    }
}
