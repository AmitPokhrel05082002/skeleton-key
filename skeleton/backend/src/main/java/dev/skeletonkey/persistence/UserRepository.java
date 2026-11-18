package dev.skeletonkey.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Port for users and their role codes. */
public interface UserRepository {
    void insert(UserAccount user);

    Optional<UserAccount> findById(UUID id);

    /** Case-insensitive: {@code Asha} and {@code asha} find the same user. */
    Optional<UserAccount> findByUsername(String username);

    /** True when the user exists and is active; one primary-key lookup. A missing user is not active. */
    boolean isActive(UUID id);

    /** Active users holding the role, ordered by id. */
    List<UserAccount> findActiveByRole(String roleCode);

    /** Display names by user id, one lookup. Unknown ids are absent; an empty argument gives an empty map. */
    Map<UUID, String> findDisplayNames(Collection<UUID> userIds);

    /** Admin listing: filtered, sorted, one page. Never carries the password hash. */
    PageResult<UserRow> search(UserSearch search);

    /** Admin read of one user, without the password hash. */
    Optional<UserRow> findRow(UUID id);

    /**
     * Partial update in one transaction: a null argument leaves that property unchanged; a non-null role set replaces
     * the stored roles as a whole.
     */
    void update(UUID id, String displayName, Boolean active, Set<String> roles);

    void updatePasswordHash(UUID id, String passwordHash);
}
