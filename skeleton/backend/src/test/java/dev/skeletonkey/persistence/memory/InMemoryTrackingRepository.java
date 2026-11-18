package dev.skeletonkey.persistence.memory;

import dev.skeletonkey.persistence.TrackingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Same semantics as JdbcTrackingRepository, for fast service and web tests. */
public class InMemoryTrackingRepository implements TrackingRepository {

    /** One history row as stored (kind, statuses, time, sequence). */
    public record Row(String kind, String fromStatus, String toStatus, Instant at, long seq) {}

    /** A stored record plus, optionally, its workflow instance and history. */
    public record Stored(UUID id, String app, String reference, String entity, Map<String, Object> data,
                         Instant createdAt, UUID instanceId, int version, String workflow, String status,
                         Instant updatedAt, List<Row> history) {}

    private final List<Stored> records = new ArrayList<>();

    public synchronized void add(Stored s) {
        records.add(s);
    }

    public synchronized void clear() {
        records.clear();
    }

    @Override
    public synchronized Optional<Candidate> findCandidate(String appCode, String referenceNo) {
        return records.stream().filter(r -> r.app().equals(appCode) && referenceNo.equals(r.reference()))
                .findFirst().map(r -> new Candidate(r.id(), r.entity()));
    }

    @Override
    public synchronized boolean contactMatches(UUID recordId, String contactField, String emailLower) {
        return records.stream().filter(r -> r.id().equals(recordId)).findFirst()
                .map(r -> r.data().get(contactField))
                .filter(v -> v instanceof String)
                .map(v -> dev.skeletonkey.app.TrackingService.trimWs((String) v).toLowerCase(Locale.ROOT)
                        .equals(emailLower))
                .orElse(false);
    }

    @Override
    public synchronized Optional<Snapshot> snapshot(UUID recordId, String titleField) {
        return records.stream().filter(r -> r.id().equals(recordId) && r.instanceId() != null).findFirst()
                .map(r -> new Snapshot(r.reference(),
                        titleField == null || r.data().get(titleField) == null ? null
                                : String.valueOf(r.data().get(titleField)),
                        r.createdAt(), r.instanceId(), r.version(), r.workflow(), r.status(), r.updatedAt()));
    }

    @Override
    public synchronized List<TimelineRow> timeline(UUID instanceId) {
        return records.stream().filter(r -> instanceId.equals(r.instanceId())).findFirst()
                .map(r -> r.history().stream()
                        .filter(h -> h.toStatus() != null && (h.kind().equals("START")
                                || (List.of("ACTION", "SKIPPED", "AUTO").contains(h.kind())
                                        && !h.toStatus().equals(h.fromStatus()))))
                        .sorted(Comparator.comparing(Row::at).thenComparingLong(Row::seq))
                        .map(h -> new TimelineRow(h.at(), h.toStatus()))
                        .toList())
                .orElse(List.of());
    }
}
