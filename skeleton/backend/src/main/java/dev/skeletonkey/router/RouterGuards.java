package dev.skeletonkey.router;

import com.fasterxml.jackson.databind.JsonNode;
import dev.skeletonkey.domain.expr.EvalContext;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.expr.ExpressionException;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.Action.Presence;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Step.FieldRule;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.RouterException;
import dev.skeletonkey.record.FieldViolation;
import dev.skeletonkey.record.RecordValidator;
import dev.skeletonkey.web.ApiViolation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure guard and input validation stages of the routing pipeline (Req 9.2-9.9, 11.4-11.8).
 * Every method throws {@link RouterException} and performs no write.
 */
@org.springframework.stereotype.Component
public class RouterGuards {

  private static final Set<String> IGNORED_TYPE_CONSTRAINTS = Set.of("REQUIRED", "UNKNOWN_FIELD");

  private final ExpressionEngine engine;
  private final RecordValidator recordValidator = new RecordValidator();

  public RouterGuards(ExpressionEngine engine) {
    this.engine = engine;
  }

  /**
   * Permission stage: instance open, action on current step, role, {@code when}, assignee/escalation.
   *
   * @return the resolved current step and action
   */
  public Guarded checkPermission(Workflow wf, InstanceState inst, String actionCode,
                                 Principal principal, EvalContext ctx) {
    if (inst.ended()) {
      throw new RouterException(409, "INSTANCE_ENDED", "The workflow instance has ended");
    }
    Step step = wf.step(inst.step()).orElseThrow(() -> new RouterException(
        409, "ACTION_NOT_AVAILABLE", "The current step does not exist in the pinned definition"));
    Action action = step.action(actionCode).orElseThrow(() -> new RouterException(
        409, "ACTION_NOT_AVAILABLE",
        "Action '" + actionCode + "' is not available on the current step"));

    if (!principal.hasAnyRole(action.roles())) {
      throw new RouterException(403, "INSUFFICIENT_ROLE", "Insufficient role for this action");
    }
    if (action.when() != null && !whenHolds(action.when(), ctx)) {
      throw new RouterException(409, "ACTION_CONDITION_NOT_MET", "The action condition is not met");
    }
    if (inst.assigneeId() != null
        && !inst.assigneeId().equals(principal.userId())
        && !(step.escalateTo() != null && principal.roles().contains(step.escalateTo()))) {
      throw new RouterException(403, "NOT_ASSIGNEE",
          "The task is assigned to another user");
    }
    return new Guarded(step, action);
  }

  /**
   * Remarks, attachment and field validation.
   *
   * @param changes field values carried by the request
   * @param current the record data before the request
   */
  public void validateInput(Step step, Action action, Entity entity, Map<String, Object> changes,
                            Map<String, Object> current, String remarks, String attachment) {
    Map<String, Object> req = changes == null ? Map.of() : changes;
    Map<String, Object> cur = current == null ? Map.of() : current;

    // 9.8 / 11.4: values for fields that are not optional or required
    List<ApiViolation> denied = new ArrayList<>();
    for (Map.Entry<String, Object> e : req.entrySet()) {
      FieldRule rule = FieldRuleResolver.effectiveRule(step, e.getKey());
      if (e.getValue() != null && rule != FieldRule.OPTIONAL && rule != FieldRule.REQUIRED) {
        denied.add(new ApiViolation(e.getKey(), "readonly",
            "Field '" + e.getKey() + "' cannot be written in this step"));
      }
    }
    if (!denied.isEmpty()) {
      throw new RouterException(403, "FIELD_NOT_WRITABLE",
          "Read-only fields cannot be written: " + names(denied), denied);
    }

    // 9.6
    if (action.remarks() == Presence.REQUIRED && (remarks == null || remarks.isBlank())) {
      throw new RouterException(422, "REMARKS_REQUIRED", "Remarks are required for this action",
          List.of(new ApiViolation("remarks", "required")));
    }
    // 9.7
    if (action.attachment() == Presence.REQUIRED && (attachment == null || attachment.isBlank())) {
      throw new RouterException(422, "ATTACHMENT_REQUIRED", "An attachment is required for this action",
          List.of(new ApiViolation("attachment", "required")));
    }

    // 11.6 / 11.8: type check of present values
    List<ApiViolation> invalid = new ArrayList<>();
    if (entity != null) {
      Map<String, Object> nonNull = new java.util.LinkedHashMap<>();
      req.forEach((k, v) -> {
        if (v != null) {
          nonNull.put(k, v);
        }
      });
      for (FieldViolation v : recordValidator.validate(entity, nonNull, RecordValidator.Mode.UPDATE)) {
        if (!IGNORED_TYPE_CONSTRAINTS.contains(v.constraint())) {
          invalid.add(new ApiViolation(v.field(), v.constraint(), v.message()));
        }
      }
    }
    if (!invalid.isEmpty()) {
      throw new RouterException(422, "VALIDATION_FAILED", "Invalid field values: " + names(invalid), invalid);
    }

    // 9.9 / 11.5: required fields empty after applying the request
    List<ApiViolation> missing = new ArrayList<>();
    for (Map.Entry<String, FieldRule> e : step.fields().entrySet()) {
      if (e.getValue() != FieldRule.REQUIRED) {
        continue;
      }
      Object value = req.containsKey(e.getKey()) ? req.get(e.getKey()) : cur.get(e.getKey());
      if (isEmpty(value)) {
        missing.add(new ApiViolation(e.getKey(), "required",
            "Field '" + e.getKey() + "' is required"));
      }
    }
    if (!missing.isEmpty()) {
      throw new RouterException(422, "REQUIRED_FIELDS_MISSING",
          "Required fields are empty: " + names(missing), missing);
    }
  }

  private boolean whenHolds(JsonNode when, EvalContext ctx) {
    try {
      return engine.truthy(engine.evaluate(when, ctx));
    } catch (ExpressionException e) {
      return false; // an evaluation error counts as false
    }
  }

  private static boolean isEmpty(Object v) {
    return v == null || (v instanceof String s && s.isBlank());
  }

  private static String names(List<ApiViolation> list) {
    return String.join(", ", list.stream().map(ApiViolation::field).toList());
  }

  /** Result of the permission stage. */
  public record Guarded(Step step, Action action) {}
}
