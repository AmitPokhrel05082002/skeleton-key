package dev.skeletonkey.web;

import java.util.List;
import java.util.UUID;

/** The authenticated user as stored: id, display name and sorted role codes. Nothing else. */
public record CurrentUserResponse(UUID userId, String displayName, List<String> roles) {}
