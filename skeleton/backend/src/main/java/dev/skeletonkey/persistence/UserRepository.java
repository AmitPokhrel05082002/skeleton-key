package dev.skeletonkey.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port for users and their role codes. */
public interface UserRepository {
    void insert(UserAccount user);

    Optional<UserAccount> findById(UUID id);

    Optional<UserAccount> findByUsername(String username);

    /** Active users holding the role, ordered by id. */
    List<UserAccount> findActiveByRole(String roleCode);

    /** Display names by user id, one lookup. Unknown ids are absent; an empty argument gives an empty map. */
    Map<UUID, String> findDisplayNames(Collection<UUID> userIds);
}
