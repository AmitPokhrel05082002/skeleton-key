package dev.skeletonkey.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.domain.expr.EvalContext;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.Status;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Step.FieldRule;
import dev.skeletonkey.domain.model.Transition;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.RouterException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoutingSelectionTest {

  private static final ObjectMapper M = new ObjectMapper();
  private final TransitionSelector selector = new TransitionSelector(new ExpressionEngine());

  private static JsonNode json(String s) {
    try {
      return M.readTree(s);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Step step(String code, String status, Map<String, FieldRule> fields) {
    return new Step(code, null, Step.Type.TASK, null, status, null, null, null, null, fields,
        List.of(), List.of(), List.of(), List.of(), null, null, null, List.of(), null, null);
  }

  private static Action action(String code, String to, String status) {
    return new Action(code, code, List.of("R"), to, status, null, null, null, null, null, null);
  }

  private static Transition tr(String to, String on, String when, int priority, String status) {
    return new Transition("A", to, on, when == null ? null : json(when), priority, status);
  }

  private static Workflow wf(List<Step> steps, List<Transition> ts) {
    return new Workflow("W", null, "E", "A", null,
        List.of(new Status("Open", Status.Category.OPEN, null)), steps, ts);
  }

  private static EvalContext ctx(String data) {
    return new EvalContext(json(data), json("{}"), json("{}"));
  }

  private TransitionSelector.Selection select(Workflow w, Action a, String data) {
    return selector.select(w, w.steps().get(0), a, ctx(data), UUID.randomUUID(), Instant.now(), "Open");
  }

  private final List<Step> steps = List.of(step("A", null, Map.of()), step("B", null, Map.of()),
      step("C", null, Map.of()));

  @Test
  void unlistedFieldIsReadonly() {
    Step s = step("A", null, Map.of("x", FieldRule.REQUIRED));
    assertEquals(FieldRule.REQUIRED, FieldRuleResolver.effectiveRule(s, "x"));
    assertEquals(FieldRule.READONLY, FieldRuleResolver.effectiveRule(s, "y"));
  }

  @Test
  void statusPrecedence() {
    Transition t = tr("B", null, null, 100, "FromT");
    Action a = action("go", null, "FromA");
    Step target = step("B", "FromS", Map.of());
    assertEquals("FromT", StatusResolver.resolveStatus(t, a, target, "Cur"));
    assertEquals("FromA", StatusResolver.resolveStatus(null, a, target, "Cur"));
    assertEquals("FromS", StatusResolver.resolveStatus(null, action("go", null, null), target, "Cur"));
    assertEquals("Cur", StatusResolver.resolveStatus(null, null, step("B", null, Map.of()), "Cur"));
  }

  @Test
  void lowestPriorityWinsAndTiesUseDeclarationOrder() {
    Workflow w = wf(steps, List.of(tr("C", null, null, 50, null), tr("B", null, null, 10, null),
        tr("A", null, null, 10, null)));
    assertEquals("B", select(w, action("go", null, null), "{}").targetStep());
  }

  @Test
  void onMustMatchActionCode() {
    Workflow w = wf(steps, List.of(tr("C", "other", null, 1, null)));
    assertEquals("B", select(w, action("go", null, null), "{}").targetStep());
    assertEquals("C", select(w, action("other", null, null), "{}").targetStep());
  }

  @Test
  void whenConditionAndFallbackToActionTo() {
    Workflow w = wf(steps, List.of(tr("B", null, "{\"==\":[{\"var\":\"data.k\"},1]}", 1, "S")));
    TransitionSelector.Selection hit = select(w, action("go", "C", null), "{\"k\":1}");
    assertEquals("B", hit.targetStep());
    assertEquals("S", hit.transition().status());
    TransitionSelector.Selection miss = select(w, action("go", "C", null), "{\"k\":2}");
    assertEquals("C", miss.targetStep());
    assertNull(miss.transition());
  }

  @Test
  void expressionErrorIsRecordedAndTransitionSkipped() {
    Workflow w = wf(steps, List.of(tr("C", null, "{\"nope\":[1]}", 1, null), tr("B", null, null, 2, null)));
    TransitionSelector.Selection sel = select(w, action("go", null, null), "{}");
    assertEquals("B", sel.targetStep());
    assertEquals(1, sel.errorEntries().size());
    assertEquals("EXPRESSION_ERROR", sel.errorEntries().get(0).kind());
  }

  @Test
  void fallsBackToNextStepInOrder() {
    Workflow w = wf(steps, List.of());
    assertEquals("B", select(w, action("go", null, null), "{}").targetStep());
    assertEquals("B", select(w, null, "{}").targetStep());
  }

  @Test
  void lastStepWithNoMatchIsRejectedWith422() {
    Workflow w = wf(steps, List.of());
    RouterException e = assertThrows(RouterException.class, () ->
        selector.select(w, w.steps().get(2), action("go", null, null), ctx("{}"),
            UUID.randomUUID(), Instant.now(), "Open"));
    assertEquals(422, e.status());
    assertTrue(e.getMessage().contains("C"));
  }
}
