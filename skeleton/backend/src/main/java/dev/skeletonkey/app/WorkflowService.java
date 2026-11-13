package dev.skeletonkey.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.HistoryEntry;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.ActionOutcome;
import dev.skeletonkey.domain.route.CursorUpdate;
import dev.skeletonkey.domain.route.RouterException;
import dev.skeletonkey.persistence.AssignmentCursorRepository;
import dev.skeletonkey.persistence.HistoryRepository;
import dev.skeletonkey.persistence.InstanceRepository;
import dev.skeletonkey.persistence.RecordRepository;
import dev.skeletonkey.persistence.RecordRow;
import dev.skeletonkey.persistence.ReferenceCounter;
import dev.skeletonkey.record.FieldViolation;
import dev.skeletonkey.record.PublishedDefinitions;
import dev.skeletonkey.record.PublishedDefinitions.PublishedApp;
import dev.skeletonkey.record.RecordService;
import dev.skeletonkey.record.RecordValidator;
import dev.skeletonkey.router.Router;
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
 * Starts workflow instances and runs actions (Req 8.1, 8.7, 9.12-9.14, 12.4). Each public method is one transaction.
 * The {@link Router} is pure; this class loads its inputs, calls it, and only when it returned an outcome persists
 * in this fixed order: record data, instance (optimistic lock check), history entries in order, assignment cursor
 * updates, audit entry. Any failure rolls the whole transaction back, so no partial write and no cursor move survives.
 */
@Service
public class WorkflowService {

    public static final String HISTORY_WRITE_FAILED = "HISTORY_WRITE_FAILED";
    public static final String AUDIT_WRITE_FAILED = "AUDIT_WRITE_FAILED";
    public static final String PINNED_DEFINITION_MISSING = "PINNED_DEFINITION_MISSING";
    static final String RECORD_TARGET = "record";

    /** Result of a start: the new record, the new instance and the history rows written. */
    public record Started(RecordRow record, InstanceState instance, List<HistoryEntry> history) {}

    /** Result of an action: the record after the action, the new instance state and the history rows written. */
    public record Executed(RecordRow record, InstanceState instance, List<HistoryEntry> history) {}

    private final PublishedDefinitions definitions;
    private final RecordRepository records;
    private final InstanceRepository instances;
    private final HistoryRepository history;
    private final AssignmentCursorRepository cursors;
    private final ReferenceCounter references;
    private final AuditService audit;
    private final Router router;
    private final Clock clock;
    private final RecordValidator validator = new RecordValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public WorkflowService(PublishedDefinitions definitions, RecordRepository records, InstanceRepository instances,
                           HistoryRepository history, AssignmentCursorRepository cursors,
                           ReferenceCounter references, AuditService audit, Router router,
                           ObjectProvider<Clock> clock) {
        this(definitions, records, instances, history, cursors, references, audit, router,
                clock.getIfAvailable(Clock::systemUTC));
    }

    public WorkflowService(PublishedDefinitions definitions, RecordRepository records, InstanceRepository instances,
                           HistoryRepository history, AssignmentCursorRepository cursors,
                           ReferenceCounter references, AuditService audit, Router router, Clock clock) {
        this.definitions = definitions;
        this.records = records;
        this.instances = instances;
        this.history = history;
        this.cursors = cursors;
        this.references = references;
        this.audit = audit;
        this.router = router;
        this.clock = clock;
    }

    /**
     * Creates the record and a workflow instance pinned to the highest PUBLISHED version of the app (Req 5.4, 8.1).
     * 409 when the app has no published version, 404 for an unknown workflow, 422 for an invalid payload.
     */
    @Transactional
    public Started start(String appCode, String workflowCode, Map<String, Object> payload, Principal actor) {
        PublishedApp app = definitions.latestPublished(appCode).orElseThrow(
                () -> new Conflict("APP_NOT_PUBLISHED", "App '" + appCode + "' has no published version"));
        Workflow wf = app.definition().workflow(workflowCode).orElseThrow(
                () -> new NotFound("Workflow '" + workflowCode + "' not found in " + appCode
                        + " version " + app.version()));
        Entity entity = app.definition().entity(wf.entity()).orElseThrow(
                () -> new NotFound("Entity '" + wf.entity() + "' not found"));
        check(entity, payload);

        Instant now = Instant.now(clock);
        int year = now.atZone(ZoneOffset.UTC).getYear();
        String prefix = RecordService.referencePrefix(entity);
        String reference = ReferenceCounter.format(prefix, year, references.next(prefix, year));
        UUID actorId = actor == null ? null : actor.userId();
        RecordRow row = new RecordRow(UUID.randomUUID(), appCode, app.version(), entity.code(), reference,
                clean(payload), actorId, now, now);
        InstanceState seed = new InstanceState(UUID.randomUUID(), row.id(), appCode, app.version(), wf.code(),
                null, null, null, null, false, null, 0L, null, null);

        ActionOutcome outcome = router.start(wf, entity, seed, row.data(), actor, now);

        RecordRow stored = row.withData(outcome.newData(), now);
        records.insert(stored);
        instances.insert(outcome.newState());
        appendHistory(outcome.newState().id(), outcome.history());
        applyCursors(outcome.cursorUpdates());
        writeAudit(actorId, AuditService.CREATE, stored, outcome.newState());
        return new Started(stored, outcome.newState(), outcome.history());
    }

    /**
     * Runs one action on an instance, routing on the definition version the instance is pinned to (Req 5.5).
     * A concurrent modification surfaces as OptimisticLockingFailureException (409 CONCURRENT_MODIFICATION).
     */
    @Transactional
    public Executed execute(UUID instanceId, String actionCode, Map<String, Object> changes, String remarks,
                            String attachment, Principal actor) {
        InstanceState inst = instances.findById(instanceId).orElseThrow(
                () -> new NotFound("Workflow instance " + instanceId + " not found"));
        RecordRow row = records.findById(inst.recordId()).orElseThrow(
                () -> new NotFound("Record of workflow instance " + instanceId + " not found"));
        // Never the latest version: the instance stays on the version it started with.
        AppDefinition def = definitions.publishedVersion(inst.appCode(), inst.defVersion())
                .map(PublishedApp::definition).orElseThrow(() -> new RouterException(500,
                        PINNED_DEFINITION_MISSING, "Pinned definition " + inst.appCode() + " version "
                                + inst.defVersion() + " is not available"));
        Workflow wf = def.workflow(inst.workflowCode()).orElseThrow(() -> new RouterException(500,
                PINNED_DEFINITION_MISSING, "Workflow '" + inst.workflowCode() + "' missing in pinned definition"));
        Entity entity = def.entity(wf.entity()).orElseThrow(() -> new RouterException(500,
                PINNED_DEFINITION_MISSING, "Entity '" + wf.entity() + "' missing in pinned definition"));

        Instant now = Instant.now(clock);
        // The Router reads cursors and open-task counts through its AssigneeResolver and returns cursor moves as
        // plain data; a RouterException here means nothing was written.
        ActionOutcome outcome = router.execute(wf, entity, inst, row.data(), actionCode,
                changes == null ? Map.of() : changes, remarks, attachment, actor, now);

        boolean dataChanged = !row.data().equals(outcome.newData());
        RecordRow saved = row;
        if (dataChanged) {
            Map<String, Object> next = new LinkedHashMap<>(outcome.newData());
            records.updateData(row.id(), next, now);
            saved = row.withData(next, now);
        }
        InstanceState savedState = instances.update(outcome.newState());
        appendHistory(inst.id(), outcome.history());
        applyCursors(outcome.cursorUpdates());
        if (dataChanged) {
            writeAudit(actor == null ? null : actor.userId(), AuditService.UPDATE, saved, savedState);
        }
        return new Executed(saved, savedState, outcome.history());
    }

    private void appendHistory(UUID instanceId, List<HistoryEntry> entries) {
        try {
            for (HistoryEntry e : entries) {
                history.append(instanceId, e);
            }
        } catch (RuntimeException e) {
            // Rolls the transaction back (RuntimeException); the body never carries the cause (Req 12.4).
            RouterException failure = new RouterException(500, HISTORY_WRITE_FAILED, "History could not be written");
            failure.initCause(e);
            throw failure;
        }
    }

    private void applyCursors(List<CursorUpdate> updates) {
        for (CursorUpdate u : updates) {
            cursors.apply(u);
        }
    }

    private void writeAudit(UUID actorId, String operation, RecordRow row, InstanceState state) {
        ObjectNode details = mapper.createObjectNode();
        details.put("referenceNo", row.referenceNo());
        details.put("entity", row.entityCode());
        details.put("defVersion", row.defVersion());
        details.put("instanceId", state.id().toString());
        try {
            audit.record(actorId, operation, RECORD_TARGET, row.id().toString(), details);
        } catch (RuntimeException e) {
            RouterException failure = new RouterException(500, AUDIT_WRITE_FAILED, "Audit entry could not be written");
            failure.initCause(e);
            throw failure;
        }
    }

    private void check(Entity entity, Map<String, Object> payload) {
        List<FieldViolation> found = validator.validate(entity, payload, RecordValidator.Mode.CREATE);
        if (!found.isEmpty()) {
            throw new ValidationFailed("Record validation failed",
                    found.stream().map(v -> new ApiViolation(v.field(), v.constraint(), v.message())).toList());
        }
    }

    private static Map<String, Object> clean(Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        payload.forEach((k, v) -> {
            if (v != null) {
                out.put(k, v);
            }
        });
        return out;
    }
}
