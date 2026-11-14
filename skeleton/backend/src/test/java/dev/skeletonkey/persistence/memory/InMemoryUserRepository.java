package dev.skeletonkey.persistence.memory;

import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.UserRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryUserRepository implements UserRepository {
    private final Map<UUID, UserAccount> users = new HashMap<>();

    @Override
    public synchronized void insert(UserAccount user) {
        if (users.containsKey(user.id())
                || users.values().stream().anyMatch(u -> u.username().equals(user.username()))) {
            throw new IllegalStateException("Duplicate user " + user.username());
        }
        users.put(user.id(), user);
    }

    @Override
    public synchronized Optional<UserAccount> findById(UUID id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public synchronized Optional<UserAccount> findByUsername(String username) {
        return users.values().stream().filter(u -> u.username().equals(username)).findFirst();
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
    }

    @Override
    public synchronized List<UserAccount> findActiveByRole(String roleCode) {
        return users.values().stream()
                .filter(u -> u.active() && u.roles().contains(roleCode))
                .sorted(Comparator.comparing(UserAccount::id))
                .toList();
    }
}
