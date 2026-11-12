package dev.skeletonkey.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.domain.expr.EvalContext;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.expr.ExpressionException;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.HistoryEntry;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Transition;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.RouterException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Picks the next step (Req 10.1-10.7): matching transition with the lowest priority (ties by
 * declaration order), else the action's {@code to}, else the next step in array order.
 */
public class TransitionSelector {

  public static final String EXPRESSION_ERROR = "EXPRESSION_ERROR";

  /**
   * @param transition the selected transition, or null when the target came from action.to / array order
   * @param targetStep code of the step to enter
   * @param errorEntries EXPRESSION_ERROR history entries for transitions whose expression failed
   */
  public record Selection(Transition transition, String targetStep, List<HistoryEntry> errorEntries) {
    public Selection {
      errorEntries = List.copyOf(errorEntries);
    }
  }

  private final ExpressionEngine engine;
  private final ObjectMapper mapper = new ObjectMapper();

  public TransitionSelector(ExpressionEngine engine) {
    this.engine = engine;
  }

  /**
   * @param action the action taken, or null when routing onward from a skipped/AUTO step
   * @throws RouterException 422 when nothing matches, there is no {@code to} and the step is last
   */
  public Selection select(Workflow wf, Step current, Action action, EvalContext ctx,
                          UUID actorId, Instant now, String currentStatus) {
    String actionCode = action == null ? null : action.code();
    List<HistoryEntry> errors = new ArrayList<>();

    List<Transition> candidates = new ArrayList<>();
    for (Transition t : wf.transitions()) {
      if (!t.from().equals(current.code())) {
        continue;
      }
      if (t.on() != null && !t.on().equals(actionCode)) {
        continue;
      }
      candidates.add(t);
    }
    // List.sort is stable, so declaration order breaks priority ties.
    candidates.sort(Comparator.comparingInt(TransitionSelector::priorityOf));

    for (Transition t : candidates) {
      if (t.when() == null) {
        return new Selection(t, t.to(), errors);
      }
      try {
        if (engine.truthy(engine.evaluate(t.when(), ctx))) {
          return new Selection(t, t.to(), errors);
        }
      } catch (ExpressionException e) {
        errors.add(errorEntry(t, current, currentStatus, actorId, now, e));
      }
    }

    if (action != null && action.to() != null) {
      return new Selection(null, action.to(), errors);
    }
    int idx = wf.steps().indexOf(current);
    if (idx >= 0 && idx + 1 < wf.steps().size()) {
      return new Selection(null, wf.steps().get(idx + 1).code(), errors);
    }
    throw new RouterException(422, "NO_NEXT_STEP",
        "No transition matches and step '" + current.code() + "' has no next step");
  }

  private static int priorityOf(Transition t) {
    return t.priority();
  }

  private HistoryEntry errorEntry(Transition t, Step current, String status, UUID actorId,
                                  Instant now, ExpressionException e) {
    ObjectNode detail = mapper.createObjectNode();
    detail.put("kind", "TRANSITION");
    detail.put("from", t.from());
    detail.put("to", t.to());
    if (t.on() != null) {
      detail.put("on", t.on());
    }
    detail.put("priority", t.priority());
    detail.put("error", String.valueOf(e.getMessage()));
    return new HistoryEntry(EXPRESSION_ERROR, null, current.code(), current.code(), status, status,
        actorId, null, null, detail, now, 0L);
  }
}
