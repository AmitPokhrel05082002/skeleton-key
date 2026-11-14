package dev.skeletonkey.persistence;

import java.time.Instant;
import java.util.UUID;

/** A stored Definition_Version without its document (list reads never load the JSONB column). */
public record DefinitionVersionInfo(UUID id, String appCode, int version, String status, String contentHash,
                                    Instant createdAt, Instant publishedAt) {}
