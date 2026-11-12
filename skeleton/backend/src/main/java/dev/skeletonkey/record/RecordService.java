package dev.skeletonkey.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Principal;
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

    private RecordRow visible(String appCode, String entityCode, UUID id, Principal caller) {
        RecordRow row = records.findById(id)
                .filter(r -> r.appCode().equals(appCode) && r.entityCode().equals(entityCode))
                .filter(r -> isVisible(r, caller))
                .orElseThrow(() -> new NotFound("Record not found"));
        return row;
    }

    /** Row visibility: records are visible to authenticated callers; anonymous callers see none. */
    private static boolean isVisible(RecordRow row, Principal caller) {
        return caller != null && caller.userId() != null;
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
