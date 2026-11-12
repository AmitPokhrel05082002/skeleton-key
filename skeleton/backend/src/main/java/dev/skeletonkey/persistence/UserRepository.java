package dev.skeletonkey.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for users and their role codes. */
public interface UserRepository {
    void insert(UserAccount user);

    Optional<UserAccount> findById(UUID id);

    Optional<UserAccount> findByUsername(String username);

    /** Active users holding the role, ordered by id. */
    List<UserAccount> findActiveByRole(String roleCode);
}
