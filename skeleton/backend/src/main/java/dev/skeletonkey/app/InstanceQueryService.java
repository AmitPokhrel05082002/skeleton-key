package dev.skeletonkey.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.domain.access.RowVisibility;
import dev.skeletonkey.domain.expr.EvalContext;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Field;
import dev.skeletonkey.domain.model.HistoryEntry;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.RouterException;
import dev.skeletonkey.persistence.HistoryRepository;
import dev.skeletonkey.persistence.InstanceRepository;
import dev.skeletonkey.persistence.RecordRepository;
import dev.skeletonkey.persistence.RecordRow;
import dev.skeletonkey.record.PublishedDefinitions;
import dev.skeletonkey.record.PublishedDefinitions.PublishedApp;
import dev.skeletonkey.router.FieldRuleResolver;
import dev.skeletonkey.router.RouterGuards;
import dev.skeletonkey.web.NotFound;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Instance_View, history read and the action entry point (Req 9.1, 12.5-12.7, 15). Everything is evaluated against
 * the definition version the instance is pinned to, never the latest (Req 15.7). Reads of an instance that does not
 * exist or is not visible to the caller are 404 (Req 15.5, 12.6).
 */
@Service
public class InstanceQueryService {

    private final PublishedDefinitions definitions;
    private final InstanceRepository instances;
    private final RecordRepository records;
    private final HistoryRepository history;
    private final RouterGuards guards;
    private final WorkflowService workflow;
    private final ObjectMapper mapper = new ObjectMapper();

    public InstanceQueryService(PublishedDefinitions definitions, InstanceRepository instances,
                                RecordRepository records, HistoryRepository history, RouterGuards guards,
                                WorkflowService workflow) {
        this.definitions = definitions;
        this.instances = instances;
        this.records = records;
        this.history = history;
        this.guards = guards;
        this.workflow = workflow;
    }

    /** Instance_View of a visible instance; 404 otherwise. */
    @Transactional(readOnly = true)
    public InstanceView view(UUID instanceId, Principal caller) {
        Loaded l = loadVisible(instanceId, caller);
        return build(l.pinned, l.inst, l.record.data(), caller);
    }

    /** History of a visible instance ordered by event time, then write order; 404 otherwise. */
    @Transactional(readOnly = true)
    public List<HistoryEntry> history(UUID instanceId, Principal caller) {
        Loaded l = loadVisible(instanceId, caller);
        List<HistoryEntry> out = new ArrayList<>(history.findByInstance(l.inst.id()));
        // Stable sort: equal event times keep the order in which the repository returned (write order).
        out.sort(Comparator.comparing(HistoryEntry::eventTime, Comparator.nullsFirst(Comparator.naturalOrder())));
        return out;
    }

    /** Runs one action through the single routing pipeline and returns the Instance_View afterwards. */
    public InstanceView execute(UUID instanceId, String actionCode, Map<String, Object> fields, String remarks,
                                String attachment, Principal caller) {
        WorkflowService.Executed done = workflow.execute(instanceId, actionCode, fields, remarks, attachment, caller);
        Pinned pinned = pinned(done.instance());
        return build(pinned, done.instance(), done.record().data(), caller);
    }

    // ---- internals ---------------------------------------------------------------------------------------

    private record Pinned(Workflow wf, Entity entity, AppDefinition def) {}

    private record Loaded(InstanceState inst, RecordRow record, Pinned pinned) {}

    private Loaded loadVisible(UUID instanceId, Principal caller) {
        InstanceState inst = instances.findById(instanceId).orElseThrow(NotFoundInstance::new);
        RecordRow row = records.findById(inst.recordId()).orElseThrow(NotFoundInstance::new);
        Pinned pinned = pinned(inst);
        RowVisibility visibility = RowVisibility.of(pinned.def, pinned.entity.code(), caller);
        if (!visibility.isVisible(row.createdBy(), inst)) {
            throw new NotFoundInstance();
        }
        return new Loaded(inst, row, pinned);
    }

    private Pinned pinned(InstanceState inst) {
        AppDefinition def = definitions.publishedVersion(inst.appCode(), inst.defVersion())
                .map(PublishedApp::definition).orElseThrow(() -> missing(inst));
        Workflow wf = def.workflow(inst.workflowCode()).orElseThrow(() -> missing(inst));
        Entity entity = def.entity(wf.entity()).orElseThrow(() -> missing(inst));
        return new Pinned(wf, entity, def);
    }

    private static RouterException missing(InstanceState inst) {
        return new RouterException(500, WorkflowService.PINNED_DEFINITION_MISSING,
                "Pinned definition " + inst.appCode() + " version " + inst.defVersion() + " is not available");
    }

    private InstanceView build(Pinned p, InstanceState inst, Map<String, Object> data, Principal caller) {
        Step step = p.wf.step(inst.step()).orElse(null);
        List<InstanceView.AvailableAction> actions = new ArrayList<>();
        if (step != null && !inst.ended()) {
            EvalContext ctx = context(data, inst, caller);
            for (Action a : step.actions()) {
                try {
                    guards.checkPermission(p.wf, inst, a.code(), caller, ctx);
                } catch (RouterException e) {
                    continue; // role, when (errors count as false), assignee or escalation check failed
                }
                actions.add(new InstanceView.AvailableAction(a.code(), a.label(), a.style().name().toLowerCase(),
                        a.confirm(), a.remarks().name().toLowerCase(), a.attachment().name().toLowerCase()));
            }
        }
        Map<String, String> rules = new LinkedHashMap<>();
        for (Field f : p.entity.fields()) {
            rules.put(f.code(), FieldRuleResolver.effectiveRule(step, f.code()).name().toLowerCase());
        }
        return new InstanceView(inst.id(), inst.recordId(), inst.step(), inst.status(), inst.ended(), inst.dueAt(),
                inst.assigneeId(), actions, rules);
    }

    private EvalContext context(Map<String, Object> data, InstanceState inst, Principal caller) {
        ObjectNode instance = mapper.createObjectNode();
        instance.put("status", inst.status());
        instance.put("step", inst.step());
        if (inst.startedAt() != null) {
            instance.put("createdAt", inst.startedAt().toString());
        }
        ObjectNode user = mapper.createObjectNode();
        if (caller.userId() != null) {
            user.put("id", caller.userId().toString());
        }
        var roles = user.putArray("roles");
        caller.roles().stream().sorted().forEach(roles::add);
        return new EvalContext(mapper.valueToTree(data), instance, user);
    }

    /** 404 for an unknown or invisible instance; both look the same to the caller. */
    private static final class NotFoundInstance extends NotFound {
        NotFoundInstance() {
            super("Workflow instance not found");
        }
    }
}
