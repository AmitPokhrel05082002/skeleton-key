package dev.skeletonkey.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: skeleton-backend, Property 16: History continuity, content and stability.
 *
 * <p>Validates: Requirements 8.6, 9.12, 12.1, 12.2, 12.3, 12.5, 12.8, 12.9, 12.12, 19.8
 *
 * <p>Runs {@code Router.start} followed by up to 50 {@code Router.execute} calls over generated definitions and
 * concatenates the history of every successful outcome. Checked here, without persistence:
 * <ul>
 *   <li>continuity of START / ACTION / SKIPPED rows (to-step and to-status of a row equal the from-step and
 *       from-status of the next), the first row being START with null from-fields (12.2, 12.9, 19.8);</li>
 *   <li>exactly one START per instance and exactly one ACTION per accepted action, carrying code, actor, remarks
 *       and attachment as given and a non-negative time in step (8.6, 9.12, 12.1);</li>
 *   <li>SKIPPED rows only for steps that declare {@code skipWhen}, with actor and equal from/to status (12.3);</li>
 *   <li>the history list is append-only: earlier rows are never altered by later calls;</li>
 *   <li>rejected calls (including a call on an ended instance) return no outcome, so contribute no rows;</li>
 *   <li>event times are never decreasing, and are {@link Instant}s (the UTC millisecond ISO 8601 rendering of
 *       12.12 is the Jackson {@code InstantSerializer}, not decided by the Router).</li>
 * </ul>
 *
 * <p>Left to WorkflowService / persistence tests (need a store): read ordering by event time then write order
 * (12.5), identical entries on repeated reads (12.8), the 2000-character remarks limit on the request (12.1,
 * enforced by the controller/service boundary) and the serialised ISO 8601 millisecond format (12.12).
 */
class HistoryContinuityPropertyTest {

  private static final ObjectMapper M = new ObjectMapper();
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  record Scenario(AppDefinition def, List<ActionAttempt> attempts) {}

  @Provide
  Arbitrary<Scenario> scenarios() {
    return DefinitionArbitraries.validDefinition(6, 2, 12).map(g -> g.model()).flatMap(def -> {
      Workflow wf = def.workflows().get(0);
      return ActionSequenceArbitraries.attempt(def, wf).list().ofMinSize(1).ofMaxSize(50)
          .map(list -> new Scenario(def, list));
    });
  }

  private static Router newRouter(AppDefinition def) {
    InMemoryUserRepository users = new InMemoryUserRepository();
    int n = 1;
    for (Role r : def.roles()) {
      for (int k = 0; k < 2; k++) {
        users.insert(new UserAccount(new UUID(0, n), "u" + n, "x", "U" + n, true, Set.of(r.code())));
        n++;
      }
    }
    Map<String, PluginHandler> beans = new LinkedHashMap<>();
    for (String h : DefinitionArbitraries.HANDLER_NAMES) {
      beans.put(h, ctx -> HandlerResult.none());
    }
    ExpressionEngine engine = new ExpressionEngine();
    HandlerRegistry registry = new HandlerRegistry(beans);
    EventRunner events = new EventRunner(engine, registry);
    TransitionSelector selector = new TransitionSelector(engine);
    return new Router(engine, new RouterGuards(engine), new ActionStages(registry, events, selector), selector,
        events, new AssigneeResolver(users, new InMemoryInstanceRepository(), new InMemoryAssignmentCursorRepository()),
        registry);
  }

  private static List<String> render(List<HistoryEntry> rows) {
    return rows.stream().map(HistoryEntry::toString).collect(Collectors.toList());
  }

  @Property(tries = 250)
  void historyIsContinuousCompleteAndAppendOnly(@ForAll("scenarios") Scenario s) {
    AppDefinition def = s.def();
    Workflow wf = def.workflows().get(0);
    Entity entity = def.entity(wf.entity()).orElseThrow();
    Router router = newRouter(def);

    Principal starter = new Principal(new UUID(9, 9),
        def.roles().stream().map(Role::code).collect(Collectors.toSet()));
    InstanceState seed = new InstanceState(new UUID(1, 1), new UUID(2, 2), def.app().code(), 1, wf.code(), null,
        null, null, null, false, null, 0L, null, null);

    ActionOutcome started;
    try {
      started = router.start(wf, entity, seed, new LinkedHashMap<>(), starter, T0);
    } catch (RouterException e) {
      Assume.that(false); // a generated workflow the router cannot enter is not what this property is about
      return;
    }

    List<HistoryEntry> all = new ArrayList<>(started.history());
    InstanceState state = started.newState();
    Map<String, Object> data = new LinkedHashMap<>(started.newData());
    int accepted = 0;
    List<String> expectedActions = new ArrayList<>();
    List<String> expectedRemarks = new ArrayList<>();
    List<String> expectedAttachments = new ArrayList<>();
    List<Long> expectedInStep = new ArrayList<>();
    List<Principal> actorOfAction = new ArrayList<>();

    for (int i = 0; i < s.attempts().size(); i++) {
      ActionAttempt at = s.attempts().get(i);
      Instant now = T0.plusMillis(1500L * (i + 1) + at.pick());

      Step step = wf.step(state.step()).orElseThrow();
      String code = at.actionCode();
      final String wanted = code;
      Action chosen = step.actions().stream().filter(a -> a.code().equals(wanted)).findFirst().orElse(null);
      if (chosen == null && !"UNKNOWN_ACTION".equals(code) && !step.actions().isEmpty()) {
        chosen = step.actions().get(at.pick() % step.actions().size());
        code = chosen.code();
      }
      Principal principal;
      if (chosen != null && at.pick() % 4 != 0) {
        UUID uid = state.assigneeId() != null ? state.assigneeId() : at.actor().userId();
        principal = new Principal(uid, Set.copyOf(chosen.roles()));
      } else {
        principal = at.actor();
      }
      String remarks = at.remarks() != null ? at.remarks() : (at.pick() % 2 == 1 ? "ok" : null);
      String attachment = at.hasAttachment() ? "att-" + i : null;
      Map<String, Object> changes = M.convertValue(at.dataChanges(), new com.fasterxml.jackson.core.type.TypeReference<
          LinkedHashMap<String, Object>>() {});

      List<String> before = render(all);
      Map<String, Object> dataBefore = new LinkedHashMap<>(data);
      InstanceState stateBefore = state;

      ActionOutcome out;
      try {
        out = router.execute(wf, entity, state, data, code, changes, remarks, attachment, principal, now);
      } catch (RouterException rejected) {
        // rejected: no rows, nothing changed
        assertEquals(before, render(all), "rejected call changed history");
        assertEquals(dataBefore, data, "rejected call changed data");
        assertEquals(stateBefore, state, "rejected call changed state");
        continue;
      }

      long inStep = state.stepEnteredAt() == null ? 0L
          : Math.max(0L, Duration.between(state.stepEnteredAt(), now).toMillis());
      expectedActions.add(code);
      expectedRemarks.add(remarks);
      expectedAttachments.add(attachment);
      expectedInStep.add(inStep);
      actorOfAction.add(principal);
      accepted++;

      assertFalse(out.history().isEmpty(), "accepted call wrote no history");
      all.addAll(out.history());
      state = out.newState();
      data = new LinkedHashMap<>(out.newData());

      // append-only: earlier rows are exactly as before
      assertEquals(before, render(all.subList(0, before.size())), "earlier history rows were altered");

      if (state.ended()) {
        // an ended instance accepts nothing and so contributes no rows
        List<String> frozen = render(all);
        try {
          router.execute(wf, entity, state, data, code, Map.of(), "x", null, principal, now.plusMillis(1));
          throw new AssertionError("action on an ended instance was accepted");
        } catch (RouterException expected) {
          assertEquals(frozen, render(all));
        }
        break;
      }
    }

    // ---- continuity over START / ACTION / SKIPPED only (12.2, 12.9, 19.8)
    List<HistoryEntry> chain = all.stream()
        .filter(h -> h.kind().equals(Router.START) || h.kind().equals(Router.ACTION)
            || h.kind().equals(Router.SKIPPED) || h.kind().equals(Router.AUTO))
        .toList();
    assertFalse(chain.isEmpty());
    HistoryEntry first = chain.get(0);
    assertEquals(Router.START, first.kind());
    assertNull(first.fromStep());
    assertNull(first.fromStatus());
    assertNotNull(first.toStep());
    assertNotNull(first.toStatus());
    for (int i = 1; i < chain.size(); i++) {
      HistoryEntry prev = chain.get(i - 1);
      HistoryEntry cur = chain.get(i);
      assertEquals(prev.toStep(), cur.fromStep(),
          "step continuity broken between rows " + (i - 1) + " and " + i + ": " + prev + " -> " + cur);
      assertEquals(prev.toStatus(), cur.fromStatus(),
          "status continuity broken between rows " + (i - 1) + " and " + i + ": " + prev + " -> " + cur);
    }

    // ---- exactly one START; one ACTION per accepted action with the content given (8.6, 9.12, 12.1)
    assertEquals(1, all.stream().filter(h -> h.kind().equals(Router.START)).count());
    assertEquals(starter.userId(), first.actorId());
    List<HistoryEntry> actions = all.stream().filter(h -> h.kind().equals(Router.ACTION)).toList();
    assertEquals(accepted, actions.size(), "one ACTION row per accepted action");
    for (int i = 0; i < actions.size(); i++) {
      HistoryEntry a = actions.get(i);
      assertEquals(expectedActions.get(i), a.actionCode());
      assertEquals(actorOfAction.get(i).userId(), a.actorId());
      assertEquals(expectedRemarks.get(i), a.remarks());
      assertEquals(expectedAttachments.get(i), a.attachmentRef());
      assertTrue(a.timeInStepMs() >= 0, "negative time in step");
      assertEquals(expectedInStep.get(i), a.timeInStepMs());
      assertNotNull(a.fromStep());
      assertNotNull(a.toStep());
      assertNotNull(a.fromStatus());
      assertNotNull(a.toStatus());
    }

    // ---- SKIPPED rows (12.3)
    for (HistoryEntry h : all) {
      if (h.kind().equals(Router.SKIPPED)) {
        Step skipped = wf.step(h.fromStep()).orElseThrow();
        assertNotNull(skipped.skipWhen(), "SKIPPED row for a step without skipWhen: " + h);
        assertNotNull(h.toStep());
        assertNotNull(h.fromStatus());
        assertNotNull(h.toStatus());
        assertNotNull(h.actorId());
        assertTrue(h.timeInStepMs() >= 0);
      }
      if (h.kind().equals(Router.AUTO)) {
        Step auto = wf.step(h.fromStep()).orElseThrow();
        assertEquals(Step.Type.AUTO, auto.type(), "AUTO row for a non-AUTO step: " + h);
        assertNull(h.actionCode());
        assertEquals(0L, h.timeInStepMs());
        assertNotNull(h.toStep());
        assertNotNull(h.fromStatus());
        assertNotNull(h.toStatus());
        assertNotNull(h.actorId());
      }
    }

    // ---- event times never decrease and every row carries one (12.12 at the Router level)
    Instant prev = null;
    for (HistoryEntry h : all) {
      assertNotNull(h.eventTime());
      if (prev != null) {
        assertFalse(h.eventTime().isBefore(prev), "event time went backwards at " + h);
      }
      prev = h.eventTime();
    }
  }
}
