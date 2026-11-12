package dev.skeletonkey.web;

import java.util.List;
import java.util.UUID;

/** Login result. Never contains the password or its hash. */
public record LoginResponse(String token, String tokenType, long expiresInSeconds, UUID userId, String displayName,
                            List<String> roles) {}
