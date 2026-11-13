package dev.skeletonkey.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.config.HandlerRegistry;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.HistoryEntry;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Role;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.ActionOutcome;
import dev.skeletonkey.domain.route.CursorUpdate;
import dev.skeletonkey.domain.route.RouterException;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.memory.InMemoryAssignmentCursorRepository;
import dev.skeletonkey.persistence.memory.InMemoryInstanceRepository;
import dev.skeletonkey.persistence.memory.InMemoryUserRepository;
import dev.skeletonkey.plugin.HandlerResult;
import dev.skeletonkey.plugin.PluginHandler;
import dev.skeletonkey.testing.ActionSequenceArbitraries;
import dev.skeletonkey.testing.ActionSequenceArbitraries.ActionAttempt;
import dev.skeletonkey.testing.DefinitionArbitraries;
import dev.skeletonkey.testing.PrincipalArbitraries;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: skeleton-backend, Property 14: Routing invariants over action sequences.
 *
 * <p><b>Validates: Requirements 9.12, 19.1, 19.6, 19.7</b>
 *
 * <p>Drives {@link Router#start} and then up to 50 {@link Router#execute} calls over generated definitions against
 * in-memory port twins. After every call: the step and status are declared, an ended instance sits on an END step
 * with its outcome and accepts no action, a live instance is on a non-END step with an action, and the lock version
 * and start facts never change. A rejected call leaves the state usable for the next call.
 */
class RoutingInvariantsPropertyTest {

  private static final ObjectMapper OM = new ObjectMapper();
  private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");
  private static final long LOCK = 7L;

  record Scenario(AppDefinition def, Principal starter, List<ActionAttempt> attempts) {}

  @Provide
  Arbitrary<Scenario> scenarios() {
    return DefinitionArbitraries.validDefinition(6, 2, 12).map(g -> g.model()).flatMap(def -> {
      var wf = def.workflows().get(0);
      return Combinators.combine(
              Arbitraries.just(def),
              Arbitraries.oneOf(PrincipalArbitraries.principal(def),
                  Arbitraries.just(PrincipalArbitraries.anonymous(def))),
              ActionSequenceArbitraries.attempt(def, wf).list().ofMinSize(1).ofMaxSize(50))
          .as(Scenario::new);
    });
  }

  private static Router newRouter(InMemoryUserRepository users, InMemoryInstanceRepository instances,
                                  InMemoryAssignmentCursorRepository cursors) {
    Map<String, PluginHandler> beans = new LinkedHashMap<>();
    for (String name : DefinitionArbitraries.HANDLER_NAMES) {
      beans.put(name, ctx -> HandlerResult.of(Map.of()));
    }
    ExpressionEngine engine = new ExpressionEngine();
    HandlerRegistry registry = new HandlerRegistry(beans);
    EventRunner events = new EventRunner(engine, registry);
    TransitionSelector selector = new TransitionSelector(engine);
    return new Router(engine, new RouterGuards(engine), new ActionStages(registry, events, selector), selector,
        events, new AssigneeResolver(users, instances, cursors), registry);
  }

  @Property(tries = 200)
  void routingInvariantsHoldOverActionSequences(@ForAll("scenarios") Scenario s) {
    AppDefinition def = s.def();
    Workflow wf = def.workflows().get(0);
    Entity entity = def.entity(wf.entity()).orElseThrow();

    InMemoryUserRepository users = new InMemoryUserRepository();
    long n = 1;
    for (Role r : def.roles()) {
      users.insert(new UserAccount(new UUID(0, n), "u" + n, "x", "U" + n, true, Set.of(r.code())));
      n++;
    }
    InMemoryInstanceRepository instances = new InMemoryInstanceRepository();
    InMemoryAssignmentCursorRepository cursors = new InMemoryAssignmentCursorRepository();
    Router router = newRouter(users, instances, cursors);

    Set<String> stepCodes = new java.util.HashSet<>();
    wf.steps().forEach(st -> stepCodes.add(st.code()));
    Set<String> labels = new java.util.HashSet<>();
    wf.statuses().forEach(st -> labels.add(st.label()));

    InstanceState seed = new InstanceState(new UUID(9, 9), new UUID(8, 8), def.app().code(), 1, wf.code(),
        null, null, null, null, false, null, LOCK, null, null);

    // ---- start
    ActionOutcome started;
    try {
      started = router.start(wf, entity, seed, new LinkedHashMap<>(), s.starter(), T0);
    } catch (RouterException e) {
      // A generated graph can loop through skipWhen steps; the Router reports that as a 500 and is not under test.
      Assume.that(!Router.SKIP_LOOP.equals(e.code()) && !Router.ROUTING_LOOP.equals(e.code()));
      fail("start rejected: " + e.status() + " " + e.code() + " " + e.getMessage());
      return;
    }
    for (CursorUpdate c : started.cursorUpdates()) {
      cursors.apply(c);
    }
    InstanceState inst = started.newState();
    Map<String, Object> data = started.newData();
    UUID startedBy = inst.startedBy();
    Instant startedAt = inst.startedAt();
    assertEquals(s.starter().userId(), startedBy, "start records the starter");
    assertNotNull(startedAt);
    check(wf, stepCodes, labels, inst, seed, startedBy, startedAt, "after start");

    // ---- sequence
    int i = 0;
    for (ActionAttempt a : s.attempts()) {
      i++;
      Instant now = T0.plusSeconds(i);
      String where = "after attempt " + i + " (" + a.actionCode() + ")";

      String code = a.actionCode();
      Principal actor = a.actor();
      Step cur = wf.step(inst.step()).orElseThrow();
      if (!inst.ended() && !cur.actions().isEmpty() && a.pick() % 4 != 0
          && !"UNKNOWN_ACTION".equals(a.actionCode())) {
        // steer towards progress: choose among the actions that exist on the current step, as a permitted caller
        Action chosen = cur.actions().get(a.pick() % cur.actions().size());
        code = chosen.code();
        actor = new Principal(inst.assigneeId() != null ? inst.assigneeId() : a.actor().userId(),
            Set.copyOf(chosen.roles()));
      }

      Map<String, Object> changes = OM.convertValue(a.dataChanges(),
          new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
      String attachment = a.hasAttachment() ? "file.pdf" : null;

      Map<String, UUID> cursorsBefore = new LinkedHashMap<>();
      for (String sc : stepCodes) {
        cursors.findLastUser(def.app().code(), wf.code(), sc).ifPresent(u -> cursorsBefore.put(sc, u));
      }
      InstanceState before = inst;
      Map<String, Object> dataBefore = new LinkedHashMap<>(data);
      boolean wasEnded = inst.ended();

      ActionOutcome out = null;
      RouterException rejected = null;
      try {
        out = router.execute(wf, entity, inst, data, code, changes, a.remarks(), attachment, actor, now);
      } catch (RouterException e) {
        rejected = e;
      }

      if (wasEnded) {
        assertNotNull(rejected, "an ended instance accepts no action " + where);
        assertEquals(409, rejected.status(), where);
      }

      if (rejected != null) {
        // rejected: inputs are untouched, no cursor moved, and the same state serves the next call
        assertEquals(before, inst, "state untouched " + where);
        assertEquals(dataBefore, data, "data untouched " + where);
        Map<String, UUID> cursorsAfter = new LinkedHashMap<>();
        for (String sc : stepCodes) {
          cursors.findLastUser(def.app().code(), wf.code(), sc).ifPresent(u -> cursorsAfter.put(sc, u));
        }
        assertEquals(cursorsBefore, cursorsAfter, "cursors untouched " + where);
        check(wf, stepCodes, labels, inst, seed, startedBy, startedAt, where + " (rejected)");
        continue;
      }

      for (CursorUpdate c : out.cursorUpdates()) {
        cursors.apply(c);
      }
      inst = out.newState();
      data = out.newData();
      long actionRows = out.history().stream().filter(h -> Router.ACTION.equals(h.kind())).count();
      assertEquals(1L, actionRows, "exactly one ACTION history row " + where);
      for (HistoryEntry h : out.history()) {
        if (h.toStep() != null) {
          assertTrue(stepCodes.contains(h.toStep()), "history names a declared step " + where);
        }
        if (h.toStatus() != null) {
          assertTrue(labels.contains(h.toStatus()), "history status is declared " + where);
        }
      }
      check(wf, stepCodes, labels, inst, seed, startedBy, startedAt, where);
    }
  }

  private static void check(Workflow wf, Set<String> stepCodes, Set<String> labels, InstanceState inst,
                            InstanceState seed, UUID startedBy, Instant startedAt, String where) {
    assertTrue(stepCodes.contains(inst.step()), "step declared: " + inst.step() + " " + where);
    assertTrue(labels.contains(inst.status()), "status declared: " + inst.status() + " " + where);
    Step step = wf.step(inst.step()).orElseThrow();

    if (inst.ended()) {
      assertEquals(Step.Type.END, step.type(), "ended instance is on an END step " + where);
      assertEquals(step.outcome(), inst.status(), "ended instance carries the outcome " + where);
      assertNull(inst.assigneeId(), "ended: no assignee " + where);
      assertNull(inst.dueAt(), "ended: no due time " + where);
      assertTrue(step.actions().isEmpty(), "END step has no actions " + where);
    } else {
      assertTrue(step.type() == Step.Type.TASK || step.type() == Step.Type.APPROVAL,
          "live instance waits on a task step, was " + step.type() + " " + where);
      assertTrue(!step.actions().isEmpty(), "live instance has an executable action " + where);
    }

    assertEquals(LOCK, inst.lockVersion(), "Router never changes lockVersion " + where);
    assertEquals(startedBy, inst.startedBy(), "startedBy is fixed " + where);
    assertEquals(startedAt, inst.startedAt(), "startedAt is fixed " + where);
    assertEquals(seed.id(), inst.id(), where);
    assertEquals(seed.recordId(), inst.recordId(), where);
    assertEquals(seed.appCode(), inst.appCode(), where);
    assertEquals(seed.defVersion(), inst.defVersion(), where);
    assertEquals(seed.workflowCode(), inst.workflowCode(), where);
  }
}
