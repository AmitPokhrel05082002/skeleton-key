package dev.skeletonkey.domain.route;

import dev.skeletonkey.domain.model.HistoryEntry;
import dev.skeletonkey.domain.model.InstanceState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a start or an action changes, computed by pure code and persisted by the caller in one transaction
 * (Req 9.12, 9.14). {@code cursorUpdates} are plain data so a rejected action never advances a cursor.
 *
 * @param newData record data after the request, handlers and events
 * @param newState the instance state after step entry or end detection
 * @param history entries in write order
 * @param cursorUpdates round-robin cursor moves; at most one per (app, workflow, step), the last one wins
 */
public record ActionOutcome(Map<String, Object> newData, InstanceState newState, List<HistoryEntry> history,
                            List<CursorUpdate> cursorUpdates) {
  public ActionOutcome {
    newData = Collections.unmodifiableMap(new LinkedHashMap<>(newData));
    history = List.copyOf(history);
    cursorUpdates = List.copyOf(cursorUpdates);
  }
}
