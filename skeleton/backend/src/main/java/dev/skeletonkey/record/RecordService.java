package dev.skeletonkey.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.domain.access.RowVisibility;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Field;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.View;
import dev.skeletonkey.persistence.RecordQuery;
import dev.skeletonkey.web.BadRequest;
import java.util.ArrayList;
import java.util.Set;
import dev.skeletonkey.persistence.InstanceRepository;
import dev.skeletonkey.persistence.RecordRepository;
import dev.skeletonkey.persistence.RecordRow;
import dev.skeletonkey.persistence.ReferenceCounter;
import dev.skeletonkey.record.PublishedDefinitions.PublishedApp;
import dev.skeletonkey.web.ApiViolation;
import dev.skeletonkey.web.Conflict;
import dev.skeletonkey.web.NotFound;
import dev.skeletonkey.web.ValidationFailed;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create, read and update of business records (Req 7). Each write also writes its audit entry in the same
 * transaction (Req 12.10, 12.11).
 */
@Service
public class RecordService {

    private static final String TARGET_TYPE = "record";
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;
    private static final Set<String> RESERVED = Set.of("page", "size", "sort", "view");

    private final PublishedDefinitions definitions;
    private final RecordRepository records;
    private final InstanceRepository instances;
    private final ReferenceCounter references;
    private final AuditService audit;
    private final RecordValidator validator = new RecordValidator();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;

    @Autowired
    public RecordService(PublishedDefinitions definitions, RecordRepository records, InstanceRepository instances,
                         ReferenceCounter references, AuditService audit, ObjectProvider<Clock> clock) {
        this(definitions, records, instances, references, audit, clock.getIfAvailable(Clock::systemUTC));
    }

    public RecordService(PublishedDefinitions definitions, RecordRepository records, InstanceRepository instances,
                         ReferenceCounter references, AuditService audit, Clock clock) {
        this.definitions = definitions;
        this.records = records;
        this.instances = instances;
        this.references = references;
        this.audit = audit;
        this.clock = clock;
    }

    /** Creates a record in the highest PUBLISHED version of the app (409 when there is none). */
    @Transactional
    public RecordView create(String appCode, String entityCode, Map<String, Object> payload, Principal actor) {
        PublishedApp app = published(appCode);
        Entity entity = entity(app, entityCode);
        check(entity, payload, RecordValidator.Mode.CREATE);

        Instant now = Instant.now(clock);
        String reference = ReferenceCounter.format(prefix(entity), now.atZone(ZoneOffset.UTC).getYear(),
                references.next(prefix(entity), now.atZone(ZoneOffset.UTC).getYear()));
        RecordRow row = new RecordRow(UUID.randomUUID(), appCode, app.version(), entityCode, reference,
                clean(payload), actor.userId(), now, now);
        records.insert(row);
        audit.record(actor.userId(), AuditService.CREATE, TARGET_TYPE, row.id().toString(), details(row));
        return RecordView.of(row);
    }

    /** Reads a record; 404 when it does not exist or is not visible to the caller. */
    @Transactional(readOnly = true)
    public RecordView read(String appCode, String entityCode, UUID id, Principal caller) {
        return RecordView.of(visible(appCode, entityCode, id, caller));
    }

    /**
     * Updates a record of an entity without a workflow. {@code replace} true is PUT (full replacement, required
     * fields enforced); false is PATCH (supplied keys are merged into the stored data).
     */
    @Transactional
    public RecordView update(String appCode, String entityCode, UUID id, Map<String, Object> payload,
                             boolean replace, Principal actor) {
        PublishedApp app = published(appCode);
        Entity entity = entity(app, entityCode);
        RecordRow row = visible(appCode, entityCode, id, actor);

        if (instances.findByRecordId(id).isPresent()
                || app.definition().workflows().stream().anyMatch(w -> w.entity().equals(entityCode))) {
            throw new Conflict("WORKFLOW_MANAGED", "Record changes only through workflow actions");
        }

        Map<String, Object> next;
        if (replace) {
            check(entity, payload, RecordValidator.Mode.CREATE);
            next = clean(payload);
        } else {
            check(entity, payload, RecordValidator.Mode.UPDATE);
            next = new LinkedHashMap<>(row.data());
            payload.forEach((k, v) -> {
                if (v == null) next.remove(k);
                else next.put(k, v);
            });
        }
        Instant now = Instant.now(clock);
        records.updateData(id, next, now);
        RecordRow saved = row.withData(next, now);
        audit.record(actor.userId(), AuditService.UPDATE, TARGET_TYPE, id.toString(), details(saved));
        return RecordView.of(saved);
    }

    private PublishedApp published(String appCode) {
        return definitions.latestPublished(appCode)
                .orElseThrow(() -> new Conflict("APP_NOT_PUBLISHED", "App '" + appCode + "' is not published"));
    }

    private static Entity entity(PublishedApp app, String entityCode) {
        return app.definition().entity(entityCode)
                .orElseThrow(() -> new NotFound("Entity '" + entityCode + "' not found"));
    }

    /** Lists records of an entity (Req 7.10-7.14). {@code params} holds page, size, sort, view and filters. */
    @Transactional(readOnly = true)
    public RecordPage list(String appCode, String entityCode, Map<String, String> params, Principal caller) {
        PublishedApp app = published(appCode);
        Entity entity = entity(app, entityCode);
        List<ApiViolation> bad = new ArrayList<>();
        int page = intParam(params, "page", 1, 1, Integer.MAX_VALUE, bad);
        int size = intParam(params, "size", DEFAULT_SIZE, 1, MAX_SIZE, bad);

        View view = null;
        String viewCode = params.get("view");
        if (viewCode != null) {
            view = app.definition().views().stream()
                    .filter(v -> v.code().equals(viewCode) && v.entity().equals(entityCode)
                            && (v.roles().isEmpty() || caller.hasAnyRole(v.roles())))
                    .findFirst().orElse(null);
            if (view == null) {
                bad.add(new ApiViolation("view", "UNKNOWN_VIEW", "Unknown view '" + viewCode + "'"));
            }
        }

        Map<String, String> filters = new LinkedHashMap<>();
        params.forEach((name, value) -> {
            if (RESERVED.contains(name)) return;
            if (entity.field(name).isEmpty()) {
                bad.add(new ApiViolation(name, "UNKNOWN_FILTER", "Unknown filter field '" + name + "'"));
            } else {
                filters.put(name, value);
            }
        });

        String sortText = params.get("sort");
        RecordQuery.Sort sort = null;
        if (sortText != null && !sortText.isBlank()) {
            sort = parseSort(entity, sortText, bad);
        } else if (view != null && view.defaultSort() != null) {
            View.Sort d = view.defaultSort();
            sort = parseSort(entity, d.field() + "," + (d.direction() == null ? "DESC" : d.direction()), bad);
        } else {
            sort = new RecordQuery.Sort(RecordQuery.SortKind.CREATED_AT, null, false, true);
        }
        if (!bad.isEmpty()) {
            throw new BadRequest("Invalid list parameters", bad);
        }

        View.Scope scope = view == null || view.scope() == null ? View.Scope.ALL : view.scope();
        RowVisibility visibility = RowVisibility.of(app.definition(), entityCode, caller);
        RecordRepository.Page result = records.search(new RecordQuery(appCode, entityCode, filters, sort,
                visibility, scope == View.Scope.MINE, scope == View.Scope.ASSIGNED_TO_ME,
                scope == View.Scope.MY_ROLE_QUEUE, page, size));
        return new RecordPage(result.rows().stream().map(RecordView::of).toList(), page, size, result.total(),
                describe(sort));
    }

    private static int intParam(Map<String, String> params, String name, int def, int min, int max,
                                List<ApiViolation> bad) {
        String raw = params.get(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < min || v > max) {
                bad.add(new ApiViolation(name, "OUT_OF_RANGE", "'" + name + "' must be between " + min
                        + " and " + (max == Integer.MAX_VALUE ? "the maximum" : String.valueOf(max))));
            }
            return v;
        } catch (NumberFormatException e) {
            bad.add(new ApiViolation(name, "NOT_AN_INTEGER", "'" + name + "' must be an integer"));
            return def;
        }
    }

    private static RecordQuery.Sort parseSort(Entity entity, String text, List<ApiViolation> bad) {
        String[] parts = text.split(",", -1);
        String name = parts[0].trim();
        if (parts.length > 2 || (parts.length == 2 && !parts[1].trim().equalsIgnoreCase("asc")
                && !parts[1].trim().equalsIgnoreCase("desc"))) {
            bad.add(new ApiViolation("sort", "INVALID_SORT", "Sort direction must be asc or desc"));
            return null;
        }
        final boolean desc = parts.length == 2 && parts[1].trim().equalsIgnoreCase("desc");
        RecordQuery.SortKind system = switch (name) {
            case "$reference" -> RecordQuery.SortKind.REFERENCE;
            case "$createdAt" -> RecordQuery.SortKind.CREATED_AT;
            case "$updatedAt" -> RecordQuery.SortKind.UPDATED_AT;
            case "$createdBy" -> RecordQuery.SortKind.CREATED_BY;
            case "$status" -> RecordQuery.SortKind.STATUS;
            case "$step" -> RecordQuery.SortKind.STEP;
            case "$assignee" -> RecordQuery.SortKind.ASSIGNEE;
            case "$dueAt" -> RecordQuery.SortKind.DUE_AT;
            default -> null;
        };
        if (system != null) return new RecordQuery.Sort(system, null, false, desc);
        return entity.field(name)
                .map(f -> new RecordQuery.Sort(RecordQuery.SortKind.FIELD, name,
                        f.type() == Field.Type.NUMBER || f.type() == Field.Type.DECIMAL, desc))
                .orElseGet(() -> {
                    bad.add(new ApiViolation("sort", "UNKNOWN_FIELD", "Unknown sort field '" + name + "'"));
                    return null;
                });
    }

    private static String describe(RecordQuery.Sort s) {
        String name = s.kind() == RecordQuery.SortKind.FIELD ? s.field() : switch (s.kind()) {
            case REFERENCE -> "$reference";
            case CREATED_AT -> "$createdAt";
            case UPDATED_AT -> "$updatedAt";
            case CREATED_BY -> "$createdBy";
            case STATUS -> "$status";
            case STEP -> "$step";
            case ASSIGNEE -> "$assignee";
            default -> "$dueAt";
        };
        return name + "," + (s.descending() ? "desc" : "asc");
    }

    private RecordRow visible(String appCode, String entityCode, UUID id, Principal caller) {
        RowVisibility visibility = definitions.latestPublished(appCode)
                .map(a -> RowVisibility.of(a.definition(), entityCode, caller))
                .orElseGet(() -> RowVisibility.of(caller, false, Set.of()));
        return records.findById(id)
                .filter(r -> r.appCode().equals(appCode) && r.entityCode().equals(entityCode))
                .filter(r -> visibility.isVisible(r.createdBy(), instances.findByRecordId(r.id()).orElse(null)))
                .orElseThrow(() -> new NotFound("Record not found"));
    }

    private void check(Entity entity, Map<String, Object> payload, RecordValidator.Mode mode) {
        List<FieldViolation> found = validator.validate(entity, payload, mode);
        if (!found.isEmpty()) {
            throw new ValidationFailed("Record validation failed",
                    found.stream().map(v -> new ApiViolation(v.field(), v.constraint(), v.message())).toList());
        }
    }

    /** Drops explicit nulls so stored data only holds values. */
    private static Map<String, Object> clean(Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        payload.forEach((k, v) -> {
            if (v != null) out.put(k, v);
        });
        return out;
    }

    private static String prefix(Entity e) {
        if (e.referencePrefix() != null && !e.referencePrefix().isBlank()) return e.referencePrefix();
        String letters = e.code().replaceAll("[^A-Za-z]", "").toUpperCase();
        return letters.isEmpty() ? "REC" : letters.substring(0, Math.min(6, letters.length()));
    }

    private ObjectNode details(RecordRow row) {
        ObjectNode n = mapper.createObjectNode();
        n.put("referenceNo", row.referenceNo());
        n.put("entity", row.entityCode());
        n.put("defVersion", row.defVersion());
        return n;
    }
}
