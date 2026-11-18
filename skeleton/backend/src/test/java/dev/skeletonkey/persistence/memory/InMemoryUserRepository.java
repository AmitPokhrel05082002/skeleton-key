package dev.skeletonkey.persistence.memory;

import dev.skeletonkey.persistence.PageResult;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.UserRepository;
import dev.skeletonkey.persistence.UserRow;
import dev.skeletonkey.persistence.UserSearch;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class InMemoryUserRepository implements UserRepository {
    private final Map<UUID, UserAccount> users = new HashMap<>();
    private final Map<UUID, Instant> created = new HashMap<>();

    @Override
    public synchronized void insert(UserAccount user) {
        if (users.containsKey(user.id())
                || users.values().stream().anyMatch(u -> u.username().equalsIgnoreCase(user.username()))) {
            throw new IllegalStateException("Duplicate user " + user.username());
        }
        users.put(user.id(), user);
        created.put(user.id(), Instant.now());
    }

    @Override
    public synchronized Optional<UserAccount> findById(UUID id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public synchronized Optional<UserAccount> findByUsername(String username) {
        return users.values().stream().filter(u -> u.username().equalsIgnoreCase(username)).findFirst();
    }

    @Override
    public synchronized boolean isActive(UUID id) {
        UserAccount u = users.get(id);
        return u != null && u.active();
    }

    @Override
    public synchronized Map<UUID, String> findDisplayNames(Collection<UUID> userIds) {
        Map<UUID, String> out = new HashMap<>();
        for (UUID id : userIds) {
            UserAccount u = users.get(id);
            if (u != null) {
                out.put(id, u.displayName());
            }
        }
        return out;
    }

    /** Test hook: replaces the stored user with the same id (rename, deactivate). */
    public synchronized void put(UserAccount user) {
        users.put(user.id(), user);
        created.putIfAbsent(user.id(), Instant.now());
    }

    @Override
    public synchronized List<UserAccount> findActiveByRole(String roleCode) {
        return users.values().stream()
                .filter(u -> u.active() && u.roles().contains(roleCode))
                .sorted(Comparator.comparing(UserAccount::id))
                .toList();
    }

    @Override
    public synchronized PageResult<UserRow> search(UserSearch s) {
        String q = s.q() == null || s.q().isBlank() ? null : s.q().trim().toLowerCase(Locale.ROOT);
        Comparator<UserRow> cmp = switch (s.sortField()) {
            case USERNAME -> Comparator.comparing((UserRow r) -> r.username().toLowerCase(Locale.ROOT));
            case DISPLAY_NAME -> Comparator.comparing(
                    (UserRow r) -> r.displayName() == null ? "" : r.displayName().toLowerCase(Locale.ROOT));
            case ACTIVE -> Comparator.comparing(UserRow::active);
            case CREATED_AT -> Comparator.comparing(UserRow::createdAt);
        };
        if (!s.asc()) {
            cmp = cmp.reversed();
        }
        List<UserRow> all = users.values().stream()
                .filter(u -> q == null || u.username().toLowerCase(Locale.ROOT).contains(q)
                        || (u.displayName() != null && u.displayName().toLowerCase(Locale.ROOT).contains(q)))
                .filter(u -> s.role() == null || s.role().isBlank() || u.roles().contains(s.role()))
                .filter(u -> s.active() == null || u.active() == s.active())
                .map(this::row)
                .sorted(cmp.thenComparing(UserRow::id))
                .toList();
        int from = (int) Math.min((long) s.page() * s.size(), all.size());
        int to = Math.min(from + s.size(), all.size());
        return new PageResult<>(all.subList(from, to), s.page(), s.size(), all.size());
    }

    @Override
    public synchronized Optional<UserRow> findRow(UUID id) {
        return Optional.ofNullable(users.get(id)).map(this::row);
    }

    @Override
    public synchronized void update(UUID id, String displayName, Boolean active, Set<String> roles) {
        UserAccount u = users.get(id);
        if (u == null) {
            return;
        }
        users.put(id, new UserAccount(id, u.username(), u.passwordHash(),
                displayName != null ? displayName : u.displayName(),
                active != null ? active : u.active(),
                roles != null ? roles : u.roles()));
    }

    @Override
    public synchronized void updatePasswordHash(UUID id, String passwordHash) {
        UserAccount u = users.get(id);
        if (u != null) {
            users.put(id, new UserAccount(id, u.username(), passwordHash, u.displayName(), u.active(), u.roles()));
        }
    }

    private UserRow row(UserAccount u) {
        return new UserRow(u.id(), u.username(), u.displayName(), u.active(), u.roles(),
                created.getOrDefault(u.id(), Instant.EPOCH));
    }
}
