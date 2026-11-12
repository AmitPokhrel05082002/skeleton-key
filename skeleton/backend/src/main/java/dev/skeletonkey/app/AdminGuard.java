package dev.skeletonkey.app;

import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.domain.model.Principal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Platform_Admin check (Req 6.9). The admin role is configured by {@code skeletonkey.admin-role} and is not a role of
 * any App_Definition, so it is checked here against the Principal's role codes rather than against a definition.
 */
@Component
public class AdminGuard {
    private final String adminRole;

    @Autowired
    public AdminGuard(SkeletonProperties props) {
        this(props.adminRole());
    }

    public AdminGuard(String adminRole) {
        if (adminRole == null || adminRole.isBlank()) {
            throw new IllegalArgumentException("Admin role must not be blank");
        }
        this.adminRole = adminRole;
    }

    public String adminRole() {
        return adminRole;
    }

    public boolean isAdmin(Principal principal) {
        return principal != null && principal.roles().contains(adminRole);
    }

    /** Returns the principal when it holds the admin role, otherwise throws {@link AccessDeniedException} (403). */
    public Principal require(Principal principal) {
        if (!isAdmin(principal)) {
            throw new AccessDeniedException("Platform admin role required");
        }
        return principal;
    }
}
