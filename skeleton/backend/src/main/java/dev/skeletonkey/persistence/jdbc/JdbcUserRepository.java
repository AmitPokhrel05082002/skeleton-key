package dev.skeletonkey.persistence.jdbc;

import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.UserRepository;
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
        return jdbc.sql(SELECT + "WHERE username = :u").param("u", username)
                .query((rs, n) -> map(rs)).optional().map(this::withRoles);
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
