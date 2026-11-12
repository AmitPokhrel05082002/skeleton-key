package dev.skeletonkey.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record HistoryEntry(String kind, String actionCode, String fromStep, String toStep, String fromStatus,
                           String toStatus, UUID actorId, String remarks, String attachmentRef, JsonNode detail,
                           Instant eventTime, long timeInStepMs) {}
