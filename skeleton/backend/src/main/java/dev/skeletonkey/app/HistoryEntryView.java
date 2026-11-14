package dev.skeletonkey.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.skeletonkey.domain.model.HistoryEntry;
import java.time.Instant;
import java.util.UUID;

/** A history row as the API returns it: the stored properties plus the actor's current display name (read time). */
public record HistoryEntryView(String kind, String actionCode, String fromStep, String toStep, String fromStatus,
                               String toStatus, UUID actorId, String actorName, String remarks,
                               String attachmentRef, JsonNode detail, Instant eventTime, long timeInStepMs) {

    public static HistoryEntryView of(HistoryEntry e, String actorName) {
        return new HistoryEntryView(e.kind(), e.actionCode(), e.fromStep(), e.toStep(), e.fromStatus(), e.toStatus(),
                e.actorId(), actorName, e.remarks(), e.attachmentRef(), e.detail(), e.eventTime(), e.timeInStepMs());
    }
}
