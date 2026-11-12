package dev.skeletonkey.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.config.HandlerRegistry;
import dev.skeletonkey.domain.expr.EvalContext;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.HistoryEntry;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Step.FieldRule;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.RouterException;
import dev.skeletonkey.plugin.HandlerContext;
import dev.skeletonkey.plugin.HandlerResult;
import dev.skeletonkey.plugin.PluginHandler;
import dev.skeletonkey.record.FieldViolation;
import dev.skeletonkey.record.RecordValidator;
import dev.skeletonkey.web.ApiViolation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline stages 3 to 5 (Req 9.10, 9.11, 10.1-10.7, 13.4-13.7): Action handler run, step {@code onExit} events and
 * next-step selection. Pure: works on copies and returns a value; a {@link RouterException} means nothing was written.
 *
 * <p>Seam for the step entry loop (task 7.6): {@link Prepared#selection()} and {@link Prepared#action()} are the
 * inputs of {@code enter(target, selected, action)}; {@link Prepared#data()} is the data to enter with; and
 * {@link Prepared#history()} holds the EXPRESSION_ERROR / EVENT_* entries to prepend to the outcome history.
 */
public class ActionStages {

  public static final String UNKNOWN_HANDLER = "UNKNOWN_HANDLER";
  public static final String HANDLER_FAILED = "HANDLER_FAILED";

  /**
   * @param data record data after the request changes, handler changes and onExit events
   * @param history EVENT_FAILED / EVENT_DEFERRED (onExit) then EXPRESSION_ERROR (selection) entries
   * @param selection the chosen transition (may be null) and target step code
   */
  public record Prepared(Step step, Action action, Map<String, Object> data, List<HistoryEntry> history,
                         TransitionSelector.Selection selection) {
    public Prepared {
      data = new LinkedHashMap<>(data);
      history = List.copyOf(history);
    }
  }

  private final HandlerRegistry handlers;
  private final EventRunner events;
  private final TransitionSelector selector;
  private final RecordValidator validator = new RecordValidator();
  private final ObjectMapper mapper = new ObjectMapper();

  public ActionStages(HandlerRegistry handlers, EventRunner events, TransitionSelector selector) {
    this.handlers = handlers;
    this.events = events;
    this.selector = selector;
  }

  /**
   * @param current record data before the request
   * @param changes validated request field values (already checked by {@link RouterGuards#validateInput})
   * @param baseCtx supplies the {@code instance} and {@code user} roots
   */
  public Prepared prepare(Workflow wf, Entity entity, Step step, Action action, Map<String, Object> current,
                          Map<String, Object> changes, InstanceState inst, Principal principal,
                          EvalContext baseCtx, Instant now) {
    Map<String, Object> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
    if (changes != null) {
      changes.forEach((k, v) -> {
        if (v == null) {
          merged.remove(k);
        } else {
          merged.put(k, v);
        }
      });
    }
    Map<String, Object> data = merged;

    // Stage 3: plugin handler, once, after validation and before transition selection
    if (action.handler() != null) {
      data = runHandler(action, entity, step, data, inst, principal);
    }

    // Stage 4: onExit events of the current step
    EventRunner.Result exit = events.run(step.onExit(), entity, data, inst, principal, action.code(),
        baseCtx, now);
    data = new LinkedHashMap<>(exit.data());
    List<HistoryEntry> history = new ArrayList<>(exit.history());

    // Stage 5: transition selection against the data as it now stands
    EvalContext ctx = new EvalContext(mapper.valueToTree(data), baseCtx.instance(), baseCtx.user());
    TransitionSelector.Selection selection =
        selector.select(wf, step, action, ctx, principal.userId(), now, inst.status());
    history.addAll(selection.errorEntries());

    return new Prepared(step, action, data, history, selection);
  }

  private Map<String, Object> runHandler(Action action, Entity entity, Step step, Map<String, Object> data,
                                         InstanceState inst, Principal principal) {
    PluginHandler handler = handlers.find(action.handler()).orElseThrow(() -> new RouterException(
        500, UNKNOWN_HANDLER, "Handler '" + action.handler() + "' is not registered"));
    HandlerResult result;
    try {
      result = handler.handle(new HandlerContext(data, inst, principal, action.code()));
    } catch (RuntimeException e) {
      throw new RouterException(500, HANDLER_FAILED, "Handler '" + action.handler() + "' failed");
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

    // Req 13.5: existence, type, required and length constraints on the whole resulting record
    List<ApiViolation> violations = new ArrayList<>();
    for (FieldViolation v : validator.validate(entity, merged, RecordValidator.Mode.CREATE)) {
      violations.add(new ApiViolation(v.field(), v.constraint(), v.message()));
    }
    // Step-required fields must stay filled
    for (Map.Entry<String, FieldRule> e : step.fields().entrySet()) {
      if (e.getValue() == FieldRule.REQUIRED && violations.stream().noneMatch(v -> e.getKey().equals(v.field()))) {
        Object value = merged.get(e.getKey());
        if (value == null || (value instanceof String s && s.isBlank())) {
          violations.add(new ApiViolation(e.getKey(), "required", "Field '" + e.getKey() + "' is required"));
        }
      }
    }
    if (!violations.isEmpty()) {
      throw new RouterException(500, HANDLER_FAILED,
          "Handler '" + action.handler() + "' returned invalid field changes", violations);
    }
    return merged;
  }
}
