package dev.skeletonkey.web;

/** Body of {@code POST /api/auth/login}. Fields are validated by the service so the 400 can name the field. */
public record LoginRequest(String username, String password) {}
