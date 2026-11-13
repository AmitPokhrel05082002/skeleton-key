package dev.skeletonkey.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.config.HandlerRegistry;
import dev.skeletonkey.domain.expr.EvalContext;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.expr.ExpressionException;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.HistoryEntry;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Status;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Transition;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.ActionOutcome;
import dev.skeletonkey.domain.route.CursorUpdate;
import dev.skeletonkey.domain.route.RouterException;
import dev.skeletonkey.plugin.HandlerContext;
import dev.skeletonkey.plugin.HandlerResult;
import dev.skeletonkey.plugin.PluginHandler;
import dev.skeletonkey.record.FieldViolation;
import dev.skeletonkey.record.RecordValidator;
import dev.skeletonkey.web.ApiViolation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The routing pipeline (Req 9.1): guards, validation, handler, onExit, selection, step entry and end detection.
 * Everything is computed on copies and returned as an {@link ActionOutcome}; a {@link RouterException} means nothing
 * was written, so the router has no side effects (Req 9.14).
 */
@Component
public class Router {

  public static final String SKIP_LOOP = "SKIP_LOOP";
  public static final String UNSUPPORTED_STEP = "UNSUPPORTED_STEP";
  public static final String STEP_NOT_FOUND = "STEP_NOT_FOUND";
  public static final String NO_INITIAL_STATUS = "NO_INITIAL_STATUS";
  public static final String ROUTING_LOOP = "ROUTING_LOOP";

  public static final String START = "START";
  public static final String ACTION = "ACTION";
  public static final String SKIPPED = "SKIPPED";

  /** Safety bound for chains of AUTO steps, whose handlers may change the data and so never repeat a state. */
  static final int MAX_HOPS = 1000;

  private final ExpressionEngine engine;
  private final RouterGuards guards;
  private final ActionStages stages;
  private final TransitionSelector selector;
  private final EventRunner events;
  private final AssigneeResolver assignees;
  private final HandlerRegistry handlers;
  private final RecordValidator validator = new RecordValidator();
  private final ObjectMapper mapper = new ObjectMapper();

  public Router(ExpressionEngine engine, RouterGuards guards, ActionStages stages, TransitionSelector selector,
                EventRunner events, AssigneeResolver assignees, HandlerRegistry handlers) {
    this.engine = engine;
    this.guards = guards;
    this.stages = stages;
    this.selector = selector;
    this.events = events;
    this.assignees = assignees;
    this.handlers = handlers;
  }

  /**
   * Starts a new instance (Req 8.2, 8.3, 8.6): status is {@code initialStatus} or the first OPEN status, then the
   * start step is entered through the same loop as any other step.
   *
   * @param seed identity of the new instance (ids, app, version, workflow); step and status are filled in here
   */
  public ActionOutcome start(Workflow wf, Entity entity, InstanceState seed, Map<String, Object> data,
                             Principal principal, Instant now) {
    String initial = wf.initialStatus() != null ? wf.initialStatus()
        : wf.statuses().stream().filter(s -> s.category() == Status.Category.OPEN).map(Status::label).findFirst()
            .orElseThrow(() -> new RouterException(500, NO_INITIAL_STATUS,
                "Workflow '" + wf.code() + "' declares no initial or OPEN status"));
    UUID actor = principal == null ? null : principal.userId();
    InstanceState begin = new InstanceState(seed.id(), seed.recordId(), seed.appCode(), seed.defVersion(),
        seed.workflowCode(), wf.startStep(), initial, null, null, false, now, seed.lockVersion());

    List<HistoryEntry> history = new ArrayList<>();
    history.add(new HistoryEntry(START, null, null, wf.startStep(), null, initial, actor, null, null, null, now, 0L));
    Entry result = enter(wf, entity, begin, wf.startStep(), null, null, new LinkedHashMap<>(data), actor,
        principal, now, now, null);
    history.addAll(result.history);
    return new ActionOutcome(result.data, result.state, history, result.cursors);
  }

  /** Full pipeline: guards, validation, handler, onExit, selection, step entry (Req 9.1). */
  public ActionOutcome execute(Workflow wf, Entity entity, InstanceState inst, Map<String, Object> data,
                               String actionCode, Map<String, Object> changes, String remarks, String attachment,
                               UUID initiator, Instant createdAt, Principal principal, Instant now) {
    EvalContext ctx = context(data, inst, createdAt, principal);
    RouterGuards.Guarded guarded = guards.checkPermission(wf, inst, actionCode, principal, ctx);
    guards.validateInput(guarded.step(), guarded.action(), entity, changes, data, remarks, attachment);
    ActionStages.Prepared prepared = stages.prepare(wf, entity, guarded.step(), guarded.action(), data, changes,
        inst, principal, ctx, now);
    return complete(wf, entity, inst, prepared, remarks, attachment, initiator, createdAt, principal, now);
  }

  /**
   * Stages 6 and 7 after {@link ActionStages#prepare}: enters the selected step and assembles the outcome.
   * History order: onExit / selection entries, the ACTION row, then SKIPPED rows and entry entries in the order met.
   */
  public ActionOutcome complete(Workflow wf, Entity entity, InstanceState inst, ActionStages.Prepared prepared,
                                String remarks, String attachment, UUID initiator, Instant createdAt,
                                Principal principal, Instant now) {
    Action action = prepared.action();
    UUID actor = principal == null ? null : principal.userId();
    Entry result = enter(wf, entity, inst, prepared.selection().targetStep(), prepared.selection().transition(),
        action, prepared.data(), initiator, principal, now, createdAt, action.code());

    long inStep = inst.stepEnteredAt() == null ? 0L
        : Math.max(0L, Duration.between(inst.stepEnteredAt(), now).toMillis());
    List<HistoryEntry> history = new ArrayList<>(prepared.history());
    history.add(new HistoryEntry(ACTION, action.code(), inst.step(), result.state.step(), inst.status(),
        result.state.status(), actor, remarks, attachment == null || attachment.isBlank() ? null : attachment,
        null, now, inStep));
    history.addAll(result.history);
    return new ActionOutcome(result.data, result.state, history, result.cursors);
  }

  // ---------------------------------------------------------------------------------------------------------------

  private static final class Entry {
    Map<String, Object> data;
    InstanceState state;
    final List<HistoryEntry> history = new ArrayList<>();
    final Map<String, CursorUpdate> cursorMap = new LinkedHashMap<>();
    List<CursorUpdate> cursors;
  }

  /** The step entry loop (Req 10.8-10.13). */
  private Entry enter(Workflow wf, Entity entity, InstanceState from, String firstTarget, Transition firstSelected,
                      Action firstAction, Map<String, Object> startData, UUID initiator, Principal principal,
                      Instant now, Instant createdAt, String actionCode) {
    Entry out = new Entry();
    Map<String, Object> data = new LinkedHashMap<>(startData);
    String status = from.status();
    String targetCode = firstTarget;
    Transition selected = firstSelected;
    Action action = firstAction;
    Set<String> skipped = new HashSet<>();
    int hops = 0;

    while (true) {
      if (++hops > MAX_HOPS) {
        throw new RouterException(500, ROUTING_LOOP, "Routing did not settle on a step");
      }
      final String code = targetCode;
      Step target = wf.step(code).orElseThrow(() -> new RouterException(
          500, STEP_NOT_FOUND, "Step '" + code + "' does not exist in the pinned definition"));
      if (target.type() == Step.Type.PARALLEL || target.type() == Step.Type.JOIN
          || target.type() == Step.Type.SUBWORKFLOW) {
        throw new RouterException(500, UNSUPPORTED_STEP,
            "Step type " + target.type() + " is not supported (step '" + code + "')");
      }

      if (target.type() == Step.Type.END) {
        out.data = data;
        out.state = new InstanceState(from.id(), from.recordId(), from.appCode(), from.defVersion(),
            from.workflowCode(), target.code(), target.outcome(), null, null, true, now, from.lockVersion());
        out.cursors = List.copyOf(out.cursorMap.values());
        return out;
      }

      InstanceState view = state(from, code, status, null, null, now);
      EvalContext ctx = context(data, view, createdAt, principal);

      // skipWhen (Req 10.8, 10.9)
      if (target.skipWhen() != null) {
        boolean skip = false;
        try {
          skip = engine.truthy(engine.evaluate(target.skipWhen(), ctx));
        } catch (ExpressionException e) {
          out.history.add(skipError(target, status, principal, now, e));
        }
        if (skip) {
          if (!skipped.add(code)) {
            throw new RouterException(500, SKIP_LOOP, "Skipped steps revisit step '" + code + "'");
          }
          TransitionSelector.Selection next = selector.select(wf, target, null, ctx,
              principal == null ? null : principal.userId(), now, status);
          out.history.addAll(next.errorEntries());
          out.history.add(new HistoryEntry(SKIPPED, null, code, next.targetStep(), status, status,
              principal == null ? null : principal.userId(), null, null, null, now, 0L));
          targetCode = next.targetStep();
          selected = next.transition();
          action = null;
          continue;
        }
      }

      // status (Req 10.14): first of transition, action, step, else kept
      status = StatusResolver.resolveStatus(selected, action, target, status);
      view = state(from, code, status, null, null, now);
      EventRunner.Result onEnter = events.run(target.onEnter(), entity, data, view, principal, actionCode,
          context(data, view, createdAt, principal), now);
      data = new LinkedHashMap<>(onEnter.data());
      out.history.addAll(onEnter.history());

      if (target.type() == Step.Type.AUTO) {
        data = runAutoHandler(target, entity, data, view, principal);
        EvalContext autoCtx = context(data, view, createdAt, principal);
        TransitionSelector.Selection next = selector.select(wf, target, null, autoCtx,
            principal == null ? null : principal.userId(), now, status);
        out.history.addAll(next.errorEntries());
        targetCode = next.targetStep();
        selected = next.transition();
        action = null;
        continue;
      }

      // TASK / APPROVAL: assignee, due time, stepEnteredAt (Req 8.3, 8.4)
      AssigneeResolver.Resolution res = assignees.resolve(target.assignee(), from.appCode(), from.workflowCode(),
          code, initiator, data);
      res.cursorUpdate().ifPresent(c -> out.cursorMap.put(
          c.appCode() + "|" + c.workflowCode() + "|" + c.stepCode(), c));
      Instant due = target.slaHours() == null ? null
          : now.plus(Duration.ofMillis(Math.round(target.slaHours() * 3_600_000d)));
      out.data = data;
      out.state = new InstanceState(from.id(), from.recordId(), from.appCode(), from.defVersion(),
          from.workflowCode(), code, status, res.assignee().orElse(null), due, false, now, from.lockVersion());
      out.cursors = List.copyOf(out.cursorMap.values());
      return out;
    }
  }

  private Map<String, Object> runAutoHandler(Step step, Entity entity, Map<String, Object> data,
                                             InstanceState view, Principal principal) {
    if (step.handler() == null) {
      return data;
    }
    PluginHandler handler = handlers.find(step.handler()).orElseThrow(() -> new RouterException(
        500, ActionStages.UNKNOWN_HANDLER, "Handler '" + step.handler() + "' is not registered"));
    HandlerResult result;
    try {
      result = handler.handle(new HandlerContext(data, view, principal, null));
    } catch (RuntimeException e) {
      throw new RouterException(500, ActionStages.HANDLER_FAILED, "Handler '" + step.handler() + "' failed");
    }
    if (result == null || result.fieldChanges().isEmpty()) {
      return data;
    }
    Map<String, Object> merged = new LinkedHashMap<>(data);
    result.fieldChanges().forEach((k, v) -> {
      if (v == null) {
        merged.remove(k);
      } else {
        merged.put(k, v);
      }
    });
    List<ApiViolation> violations = new ArrayList<>();
    for (FieldViolation v : validator.validate(entity, merged, RecordValidator.Mode.CREATE)) {
      violations.add(new ApiViolation(v.field(), v.constraint(), v.message()));
    }
    if (!violations.isEmpty()) {
      throw new RouterException(500, ActionStages.HANDLER_FAILED,
          "Handler '" + step.handler() + "' returned invalid field changes", violations);
    }
    return merged;
  }

  private HistoryEntry skipError(Step step, String status, Principal principal, Instant now, ExpressionException e) {
    ObjectNode detail = mapper.createObjectNode();
    detail.put("kind", "SKIP_WHEN");
    detail.put("step", step.code());
    detail.put("error", String.valueOf(e.getMessage()));
    return new HistoryEntry(TransitionSelector.EXPRESSION_ERROR, null, step.code(), step.code(), status, status,
        principal == null ? null : principal.userId(), null, null, detail, now, 0L);
  }

  private static InstanceState state(InstanceState base, String step, String status, java.util.UUID assignee,
                                     Instant due, Instant now) {
    return new InstanceState(base.id(), base.recordId(), base.appCode(), base.defVersion(), base.workflowCode(),
        step, status, assignee, due, false, now, base.lockVersion());
  }

  private EvalContext context(Map<String, Object> data, InstanceState inst, Instant createdAt, Principal principal) {
    ObjectNode instance = mapper.createObjectNode();
    instance.put("status", inst.status());
    instance.put("step", inst.step());
    if (createdAt != null) {
      instance.put("createdAt", createdAt.toString());
    }
    ObjectNode user = mapper.createObjectNode();
    if (principal != null) {
      if (principal.userId() != null) {
        user.put("id", principal.userId().toString());
      }
      var roles = user.putArray("roles");
      principal.roles().stream().sorted().forEach(roles::add);
    }
    return new EvalContext(mapper.valueToTree(data), instance, user);
  }
}
