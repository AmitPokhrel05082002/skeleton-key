package dev.skeletonkey.domain.model;

import java.util.Set;
import java.util.UUID;

/** The caller. Anonymous callers have a null userId and hold only the public role code. */
public record Principal(UUID userId, Set<String> roles) {
    public Principal {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static Principal anonymous(String publicRoleCode) {
        return new Principal(null, Set.of(publicRoleCode));
    }

    public boolean hasAnyRole(java.util.Collection<String> codes) {
        return codes.stream().anyMatch(roles::contains);
    }
}
