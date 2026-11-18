package dev.skeletonkey.web;

import java.util.UUID;

/** Tells {@link JwtAuthFilter} whether the user named by a valid token may still use the API. */
public interface ActiveUserCheck {

    /** True when the user exists and is active. Must not throw. */
    boolean isActive(UUID userId);
}
