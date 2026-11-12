package dev.skeletonkey.domain.load;

/** One JSON Schema violation: rule is always "SCHEMA", path is an RFC 6901 JSON Pointer ("" is the root). */
public record SchemaViolation(String rule, String path, String message) {}
