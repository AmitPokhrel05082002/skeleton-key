package dev.skeletonkey.router;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.domain.expr.EvalContext;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Role;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.RouterException;
import dev.skeletonkey.testing.DefinitionArbitraries;
import dev.skeletonkey.testing.PrincipalArbitraries;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: skeleton-backend, Property 12: Role gating of actions.
 *
 * <p>Pure permission stage: accepted iff the principal holds an action role, the action {@code when} is
 * true, and the task is unassigned, assigned to the principal, or the principal holds {@code escalateTo}.
 */
class RoleGatingPropertyTest {

  private static final ObjectMapper OM = new ObjectMapper();
  private static final UUID OTHER_USER = new UUID(7L, 7L);

  private final RouterGuards guards = new RouterGuards(new ExpressionEngine());

  enum When { ABSENT, TRUE, FALSE, ERROR }

  enum AssigneeKind { NONE, SELF, OTHER }

  enum Target { KNOWN, UNKNOWN }

  record Scenario(Workflow wf, Step step, Action action, InstanceState inst, Principal principal,
                  When when, AssigneeKind assigneeKind, Target target, String escalateTo) {}

  @Provide
  Arbitrary<Scenario> scenarios() {
    return DefinitionArbitraries.validDefinition(6, 2, 8).map(g -> g.model()).flatMap(def -> {
      List<String> roleCodes = def.roles().stream().map(Role::code).toList();
      Arbitrary<Principal> principals = Arbitraries.oneOf(
          PrincipalArbitraries.principal(def),
          PrincipalArbitraries.principal(def),
          Arbitraries.just(PrincipalArbitraries.anonymous(def)));
      Arbitrary<List<String>> actionRoles = Arbitraries.of(roleCodes).list().ofMinSize(1).ofMaxSize(4);
      Arbitrary<String> escalate = Arbitraries.of(roleCodes).injectNull(0.5);
      return Combinators.combine(
              principals, actionRoles, escalate,
              Arbitraries.of(When.class), Arbitraries.of(AssigneeKind.class),
              Arbitraries.of(Target.class), Arbitraries.of(true, false, false, false),
              Arbitraries.integers().between(0, 1000))
          .as((p, roles, esc, when, ak, target, ended, pick) ->
              scenario(def, p, roles, esc, when, ak, target, ended, pick));
    });
  }

  private static Scenario scenario(AppDefinition def, Principal p, List<String> roles, String esc, When when,
                                   AssigneeKind ak, Target target, boolean ended, int pick) {
    List<Workflow> wfs = def.workflows();
    List<Step> candidates = new ArrayList<>();
    List<Workflow> owners = new ArrayList<>();
    for (Workflow w : wfs) {
      for (Step s : w.steps()) {
        if (!s.actions().isEmpty()) {
          candidates.add(s);
          owners.add(w);
        }
      }
    }
    Assume.that(!candidates.isEmpty());
    int idx = pick % candidates.size();
    Step base = candidates.get(idx);
    Workflow owner = owners.get(idx);
    Action a0 = base.actions().get(0);
    JsonNode w = switch (when) {
      case ABSENT -> null;
      case TRUE -> json("{\"==\":[1,1]}");
      case FALSE -> json("{\"==\":[1,2]}");
      case ERROR -> json("{\"noSuchOperator\":[1]}");
    };
    Action action = new Action(a0.code(), a0.label(), roles, a0.to(), a0.status(), a0.remarks(),
        a0.attachment(), a0.style(), a0.confirm(), w, a0.handler());
    Step step = new Step(base.code(), base.label(), base.type(), base.assignee(), base.status(), base.skipWhen(),
        base.slaHours(), base.remindBeforeHours(), esc, base.fields(), List.of(action), base.onEnter(),
        base.onExit(), base.branches(), base.join(), base.mode(), base.workflow(), base.copyFields(),
        base.handler(), base.outcome());
    List<Step> steps = owner.steps().stream().map(s -> s.code().equals(step.code()) ? step : s).toList();
    Workflow wf = new Workflow(owner.code(), owner.label(), owner.entity(), owner.startStep(),
        owner.initialStatus(), owner.statuses(), steps, owner.transitions());
    UUID assignee = switch (ak) {
      case NONE -> null;
      case SELF -> p.userId() != null ? p.userId() : OTHER_USER;
      case OTHER -> OTHER_USER;
    };
    InstanceState inst = new InstanceState(new UUID(1, 1), new UUID(2, 2), def.app().code(), 1, wf.code(),
        step.code(), "Open", assignee, null, ended, Instant.EPOCH, 0);
    return new Scenario(wf, step, action, inst, p, when, ak, target, esc);
  }

  private static JsonNode json(String s) {
    try {
      return OM.readTree(s);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Independent reference model: expected outcome as {status, code}, or null when accepted. */
  private static String[] expected(Scenario s) {
    boolean knownAction = s.target() == Target.KNOWN;
    if (s.inst().ended()) {
      return new String[] {"409", "INSTANCE_ENDED"};
    }
    if (!knownAction) {
      return new String[] {"409", "ACTION_NOT_AVAILABLE"};
    }
    boolean hasRole = s.action().roles().stream().anyMatch(r -> s.principal().roles().contains(r));
    if (!hasRole) {
      return new String[] {"403", "INSUFFICIENT_ROLE"};
    }
    if (s.when() == When.FALSE || s.when() == When.ERROR) {
      return new String[] {"409", "ACTION_CONDITION_NOT_MET"};
    }
    UUID assignee = s.inst().assigneeId();
    boolean unassignedQueue = assignee == null;
    boolean isAssignee = assignee != null && assignee.equals(s.principal().userId());
    boolean escalated = s.escalateTo() != null && s.principal().roles().contains(s.escalateTo());
    if (!(unassignedQueue || isAssignee || escalated)) {
      return new String[] {"403", "NOT_ASSIGNEE"};
    }
    return null;
  }

  @Property(tries = 300)
  void actionAcceptedOnlyWhenRoleWhenAndAssigneeAllow(@ForAll("scenarios") Scenario s) {
    ObjectNode data = OM.createObjectNode().put("k", "v");
    ObjectNode dataBefore = data.deepCopy();
    InstanceState instBefore = s.inst();
    String code = s.target() == Target.KNOWN ? s.action().code() : "NO_SUCH_ACTION_X";

    String[] exp = expected(s);
    RouterException thrown = null;
    RouterGuards.Guarded result = null;
    try {
      result = guards.checkPermission(s.wf(), s.inst(), code, s.principal(), new EvalContext(data, null, null));
    } catch (RouterException e) {
      thrown = e;
    }

    if (exp == null) {
      assertEquals(null, thrown == null ? null : thrown.code(), "should be accepted");
      assertEquals(s.step().code(), result.step().code());
      assertEquals(s.action().code(), result.action().code());
    } else {
      if (thrown == null) {
        throw new AssertionError("expected rejection " + exp[0] + " " + exp[1] + " but was accepted");
      }
      assertEquals(Integer.parseInt(exp[0]), thrown.status());
      assertEquals(exp[1], thrown.code());
    }
    // nothing is written: inputs unchanged
    assertEquals(dataBefore, data);
    assertEquals(instBefore, s.inst());
  }
}
