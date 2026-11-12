package dev.skeletonkey.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.domain.expr.EvalContext;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.Status;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Transition;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.RouterException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: skeleton-backend, Property 9: Transition selection precedence.
 *
 * <p><b>Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 19.5</b>
 */
class TransitionSelectionPropertyTest {

  private static final ObjectMapper M = new ObjectMapper();
  private static final String[] ACTION_CODES = {"A1", "A2", "A3"};
  private static final int[] DATA_VALUES = {1, 2, 3};

  private final TransitionSelector selector = new TransitionSelector(new ExpressionEngine());

  enum WhenKind { NONE, TRUE, FALSE, DATA, ERROR }

  record TSpec(int from, int to, String on, WhenKind kind, int dataValue, Integer priority) {}

  /** actionCode null means no action (onward routing). actionTo -1 means no {@code to}. */
  record Scenario(int stepCount, int current, List<TSpec> transitions, String actionCode, int actionTo,
                  int dataK) {}

  @Provide
  Arbitrary<Scenario> scenarios() {
    return Arbitraries.integers().between(1, 6).flatMap(n -> {
      Arbitrary<TSpec> spec = Combinators.combine(
          Arbitraries.integers().between(0, n - 1),
          Arbitraries.integers().between(0, n - 1),
          Arbitraries.of("A1", "A2", "A3").injectNull(0.5),
          Arbitraries.of(WhenKind.values()),
          Arbitraries.of(1, 2, 3),
          Arbitraries.of(0, 1, 5, 99, 100, 101, 1000).injectNull(0.3))
          .as(TSpec::new);
      // Bias "from" towards the current step so candidates are common.
      return Combinators.combine(
          Arbitraries.just(n),
          Arbitraries.integers().between(0, n - 1),
          spec.list().ofMinSize(0).ofMaxSize(20),
          Arbitraries.of("A1", "A2", "A3").injectNull(0.15),
          Arbitraries.integers().between(-1, n - 1),
          Arbitraries.of(1, 2, 3))
          .as(Scenario::new)
          .map(s -> new Scenario(s.stepCount(), s.current(), biasFrom(s), s.actionCode(), s.actionTo(),
              s.dataK()));
    });
  }

  private static List<TSpec> biasFrom(Scenario s) {
    List<TSpec> out = new ArrayList<>();
    for (int i = 0; i < s.transitions().size(); i++) {
      TSpec t = s.transitions().get(i);
      out.add(i % 3 == 2 ? t : new TSpec(s.current(), t.to(), t.on(), t.kind(), t.dataValue(), t.priority()));
    }
    return out;
  }

  private static String code(int i) {
    return "S" + i;
  }

  private static JsonNode json(String s) {
    try {
      return M.readTree(s);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode whenOf(TSpec t) {
    return switch (t.kind()) {
      case NONE -> null;
      case TRUE -> json("{\"==\":[1,1]}");
      case FALSE -> json("{\"==\":[1,2]}");
      case DATA -> json("{\"==\":[{\"var\":\"data.k\"}," + t.dataValue() + "]}");
      case ERROR -> json("{\"nope\":[1]}");
    };
  }

  private static Step step(String code) {
    return new Step(code, null, Step.Type.TASK, null, null, null, null, null, null, null,
        List.of(), List.of(), List.of(), List.of(), null, null, null, List.of(), null, null);
  }

  @Property(tries = 300)
  void transitionSelectionFollowsPrecedence(@ForAll("scenarios") Scenario sc) {
    List<Step> steps = new ArrayList<>();
    for (int i = 0; i < sc.stepCount(); i++) {
      steps.add(step(code(i)));
    }
    List<Transition> transitions = new ArrayList<>();
    for (TSpec t : sc.transitions()) {
      transitions.add(new Transition(code(t.from()), code(t.to()), t.on(), whenOf(t),
          t.priority() == null ? Transition.DEFAULT_PRIORITY : t.priority(), null));
    }
    Workflow wf = new Workflow("W", null, "E", code(0), null,
        List.of(new Status("Open", Status.Category.OPEN, null)), steps, transitions);
    Action action = sc.actionCode() == null ? null
        : new Action(sc.actionCode(), sc.actionCode(), List.of("R"),
            sc.actionTo() < 0 ? null : code(sc.actionTo()), null, null, null, null, null, null, null);
    EvalContext ctx = new EvalContext(json("{\"k\":" + sc.dataK() + "}"), json("{}"), json("{}"));
    Step current = steps.get(sc.current());

    // ---- independent reference model ----
    List<Integer> cand = new ArrayList<>();
    for (int i = 0; i < sc.transitions().size(); i++) {
      TSpec t = sc.transitions().get(i);
      boolean fromOk = t.from() == sc.current();
      boolean onOk = t.on() == null || t.on().equals(sc.actionCode());
      if (fromOk && onOk) {
        cand.add(i);
      }
    }
    // selection by repeated minimum: lowest priority, then lowest declaration index
    List<Integer> ordered = new ArrayList<>();
    List<Integer> rest = new ArrayList<>(cand);
    while (!rest.isEmpty()) {
      int best = rest.get(0);
      for (int i : rest) {
        int pi = prio(sc.transitions().get(i));
        int pb = prio(sc.transitions().get(best));
        if (pi < pb || (pi == pb && i < best)) {
          best = i;
        }
      }
      ordered.add(best);
      rest.remove(Integer.valueOf(best));
    }
    Integer winner = null;
    int expectedErrors = 0;
    for (int i : ordered) {
      TSpec t = sc.transitions().get(i);
      if (t.kind() == WhenKind.ERROR) {
        expectedErrors++;
        continue;
      }
      boolean ok = switch (t.kind()) {
        case NONE, TRUE -> true;
        case FALSE -> false;
        case DATA -> t.dataValue() == sc.dataK();
        case ERROR -> false;
      };
      if (ok) {
        winner = i;
        break;
      }
    }
    String expectedTarget;
    if (winner != null) {
      expectedTarget = code(sc.transitions().get(winner).to());
    } else if (sc.actionCode() != null && sc.actionTo() >= 0) {
      expectedTarget = code(sc.actionTo());
    } else if (sc.current() + 1 < sc.stepCount()) {
      expectedTarget = code(sc.current() + 1);
    } else {
      expectedTarget = null;
    }

    // ---- system under test ----
    if (expectedTarget == null) {
      RouterException ex = assertThrows(RouterException.class,
          () -> selector.select(wf, current, action, ctx, UUID.randomUUID(), Instant.now(), "Open"));
      assertEquals(422, ex.status());
      return;
    }
    TransitionSelector.Selection sel =
        selector.select(wf, current, action, ctx, UUID.randomUUID(), Instant.now(), "Open");

    assertEquals(expectedTarget, sel.targetStep());
    if (winner == null) {
      assertNull(sel.transition());
    } else {
      assertSame(transitions.get(winner), sel.transition());
    }
    assertEquals(expectedErrors, sel.errorEntries().size());
    sel.errorEntries().forEach(e -> {
      assertEquals(TransitionSelector.EXPRESSION_ERROR, e.kind());
      assertEquals(current.code(), e.fromStep());
      assertTrue(e.detail() != null && e.detail().has("error"));
    });
  }

  private static int prio(TSpec t) {
    return t.priority() == null ? Transition.DEFAULT_PRIORITY : t.priority();
  }
}
