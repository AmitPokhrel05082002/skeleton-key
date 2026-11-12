package dev.skeletonkey.persistence;

import java.util.Set;
import java.util.UUID;

public record UserAccount(UUID id, String username, String passwordHash, String displayName, boolean active,
                          Set<String> roles) {
    public UserAccount {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
