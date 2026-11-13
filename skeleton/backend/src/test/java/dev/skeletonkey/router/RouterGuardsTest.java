package dev.skeletonkey.router;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.domain.expr.EvalContext;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.Action.Presence;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Field;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Step.FieldRule;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.RouterException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RouterGuardsTest {

  private final ObjectMapper om = new ObjectMapper();
  private final RouterGuards guards = new RouterGuards(new ExpressionEngine());
  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  private Action action(Presence remarks, Presence attachment, String whenJson) throws Exception {
    return new Action("APPROVE", "Approve", List.of("OFFICER"), null, null, remarks, attachment, null, null,
        whenJson == null ? null : om.readTree(whenJson), null);
  }

  private Step step(Action a, Map<String, FieldRule> fields, String escalateTo) {
    return new Step("REVIEW", "Review", Step.Type.TASK, null, null, null, null, null, escalateTo, fields,
        List.of(a), null, null, null, null, null, null, null, null, null);
  }

  private Workflow wf(Step s) {
    Step end = new Step("DONE", "Done", Step.Type.END, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, "Closed");
    return new Workflow("W", "W", "E", "REVIEW", null, List.of(), List.of(s, end), List.of());
  }

  private InstanceState inst(boolean ended, UUID assignee) {
    return new InstanceState(UUID.randomUUID(), UUID.randomUUID(), "APP", 1, "W", "REVIEW", "Open", assignee,
        null, ended, Instant.now(), 0, null, Instant.now());
  }

  private Entity entity() {
    return new Entity("E", "E", null, null, List.of(
        new Field("note", "Note", Field.Type.TEXT, false, null, null, null, null, null, null, null, null, null, null),
        new Field("qty", "Qty", Field.Type.NUMBER, false, null, null, null, null, null, null, null, null, null, null),
        new Field("secret", "Secret", Field.Type.TEXT, false, null, null, null, null, null, null, null, null, null, null)));
  }

  private final Principal officer() { return new Principal(alice, Set.of("OFFICER")); }

  private RouterException perm(Workflow w, InstanceState i, String code, Principal p) {
    return assertThrows(RouterException.class,
        () -> guards.checkPermission(w, i, code, p, new EvalContext(om.createObjectNode(), null, null)));
  }

  @Test
  void endedInstanceIs409() throws Exception {
    Workflow w = wf(step(action(null, null, null), Map.of(), null));
    assertEquals(409, perm(w, inst(true, null), "APPROVE", officer()).status());
  }

  @Test
  void unknownActionIs409() throws Exception {
    Workflow w = wf(step(action(null, null, null), Map.of(), null));
    assertEquals(409, perm(w, inst(false, null), "NOPE", officer()).status());
  }

  @Test
  void missingRoleIs403() throws Exception {
    Workflow w = wf(step(action(null, null, null), Map.of(), null));
    assertEquals(403, perm(w, inst(false, null), "APPROVE", new Principal(alice, Set.of("OTHER"))).status());
  }

  @Test
  void falseWhenIs409() throws Exception {
    Workflow w = wf(step(action(null, null, "{\"==\":[1,2]}"), Map.of(), null));
    RouterException e = perm(w, inst(false, null), "APPROVE", officer());
    assertEquals(409, e.status());
    assertEquals("ACTION_CONDITION_NOT_MET", e.code());
  }

  @Test
  void otherAssigneeIs403ButEscalationRoleAndAssigneePass() throws Exception {
    Workflow w = wf(step(action(null, null, null), Map.of(), "BOSS"));
    assertEquals(403, perm(w, inst(false, bob), "APPROVE", officer()).status());
    assertDoesNotThrow(() -> guards.checkPermission(w, inst(false, alice), "APPROVE", officer(), null));
    Principal boss = new Principal(UUID.randomUUID(), Set.of("OFFICER", "BOSS"));
    assertDoesNotThrow(() -> guards.checkPermission(w, inst(false, bob), "APPROVE", boss, null));
  }

  @Test
  void roleQueueAllowsAnyRoleHolder() throws Exception {
    Workflow w = wf(step(action(null, null, null), Map.of(), null));
    assertDoesNotThrow(() -> guards.checkPermission(w, inst(false, null), "APPROVE", officer(), null));
  }

  private RouterException input(Step s, Map<String, Object> changes, Map<String, Object> cur, String remarks,
                                String attachment) {
    return assertThrows(RouterException.class, () -> guards.validateInput(
        s, s.actions().get(0), entity(), changes, cur, remarks, attachment));
  }

  @Test
  void readonlyAndUnlistedWritesRejectedWith403NamingAllFields() throws Exception {
    Step s = step(action(null, null, null), Map.of("note", FieldRule.OPTIONAL, "qty", FieldRule.READONLY), null);
    Map<String, Object> changes = new HashMap<>();
    changes.put("qty", 1);
    changes.put("secret", "x");
    changes.put("note", "ok");
    RouterException e = input(s, changes, Map.of(), null, null);
    assertEquals(403, e.status());
    assertEquals(2, e.details().size());
  }

  @Test
  void nullForReadonlyFieldIsNotAWrite() throws Exception {
    Step s = step(action(null, null, null), Map.of(), null);
    Map<String, Object> changes = new HashMap<>();
    changes.put("qty", null);
    assertDoesNotThrow(() -> guards.validateInput(s, s.actions().get(0), entity(), changes, Map.of(), null, null));
  }

  @Test
  void requiredRemarksRejectsBlank() throws Exception {
    Step s = step(action(Presence.REQUIRED, null, null), Map.of(), null);
    assertEquals(422, input(s, Map.of(), Map.of(), "   ", null).status());
    assertDoesNotThrow(() -> guards.validateInput(s, s.actions().get(0), entity(), Map.of(), Map.of(), "ok", null));
  }

  @Test
  void requiredAttachmentRejectsMissing() throws Exception {
    Step s = step(action(null, Presence.REQUIRED, null), Map.of(), null);
    assertEquals("ATTACHMENT_REQUIRED", input(s, Map.of(), Map.of(), null, null).code());
    assertDoesNotThrow(() -> guards.validateInput(s, s.actions().get(0), entity(), Map.of(), Map.of(), null, "f1"));
  }

  @Test
  void requiredFieldsMustBeFilledAfterApplyingRequest() throws Exception {
    Step s = step(action(null, null, null),
        Map.of("note", FieldRule.REQUIRED, "qty", FieldRule.REQUIRED), null);
    RouterException e = input(s, Map.of("note", "  "), Map.of("qty", 3), null, null);
    assertEquals(422, e.status());
    assertEquals(List.of("note"), e.details().stream().map(v -> v.field()).toList());
    assertDoesNotThrow(() -> guards.validateInput(
        s, s.actions().get(0), entity(), Map.of("note", "hi"), Map.of("qty", 3), null, null));
  }

  @Test
  void optionalFieldWithWrongTypeIs422AndAbsentIsAccepted() throws Exception {
    Step s = step(action(null, null, null), Map.of("qty", FieldRule.OPTIONAL), null);
    RouterException e = input(s, Map.of("qty", "abc"), Map.of(), null, null);
    assertEquals(422, e.status());
    assertTrue(e.details().stream().anyMatch(v -> v.field().equals("qty")));
    assertDoesNotThrow(() -> guards.validateInput(s, s.actions().get(0), entity(), Map.of(), Map.of(), null, null));
  }
}
