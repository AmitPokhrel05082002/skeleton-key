package dev.skeletonkey.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record AuditEntry(Instant eventTime, UUID actorId, String action, String targetType, String targetId,
                         JsonNode details) {}
