package dev.skeletonkey.persistence;

import dev.skeletonkey.domain.model.HistoryEntry;
import java.util.List;
import java.util.UUID;

/** Append-only port: there is deliberately no update or delete (Req 12.7). */
public interface HistoryRepository {
    void append(UUID instanceId, HistoryEntry entry);

    /** Entries of one instance ordered by event time, then insertion order. */
    List<HistoryEntry> findByInstance(UUID instanceId);
}
