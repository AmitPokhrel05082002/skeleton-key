package dev.skeletonkey.persistence;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** A user as the admin API reads it. Deliberately has no password or hash component. */
public record UserRow(UUID id, String username, String displayName, boolean active, Set<String> roles,
                      Instant createdAt) {
    public UserRow {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
