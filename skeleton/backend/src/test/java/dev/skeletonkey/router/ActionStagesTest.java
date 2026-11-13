package dev.skeletonkey.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.config.HandlerRegistry;
import dev.skeletonkey.domain.expr.EvalContext;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Event;
import dev.skeletonkey.domain.model.Field;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Status;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Step.FieldRule;
import dev.skeletonkey.domain.model.Transition;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.RouterException;
import dev.skeletonkey.plugin.HandlerResult;
import dev.skeletonkey.plugin.PluginHandler;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActionStagesTest {

  private static final ObjectMapper M = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");

  private final AtomicInteger calls = new AtomicInteger();
  private final Map<String, PluginHandler> beans = new LinkedHashMap<>();
  private final ActionStages stages;
  private final Entity entity = new Entity("E", "E", null, null,
      List.of(field("a", Field.Type.TEXT), field("b", Field.Type.TEXT), field("n", Field.Type.NUMBER)));
  private final InstanceState inst = new InstanceState(UUID.randomUUID(), UUID.randomUUID(), "APP", 1, "W",
      "S1", "Open", null, null, false, NOW, 0L, null, NOW);
  private final Principal user = new Principal(UUID.randomUUID(), Set.of("R"));
  private final EvalContext base = new EvalContext(null, json("{\"status\":\"Open\"}"), json("{}"));

  ActionStagesTest() {
    beans.put("setB", ctx -> {
      calls.incrementAndGet();
      return HandlerResult.of(Map.of("b", "from-handler"));
    });
    beans.put("boom", ctx -> {
      throw new IllegalStateException("secret internal detail");
    });
    beans.put("ghostField", ctx -> HandlerResult.of(Map.of("ghost", 1)));
    beans.put("badType", ctx -> HandlerResult.of(Map.of("n", "not-a-number")));
    beans.put("nothing", ctx -> null);
    ExpressionEngine engine = new ExpressionEngine();
    HandlerRegistry registry = new HandlerRegistry(beans);
    stages = new ActionStages(registry, new EventRunner(engine, registry), new TransitionSelector(engine));
  }

  private static Field field(String code, Field.Type type) {
    return new Field(code, code, type, false, null, null, List.of(), null, null, null, null, null, null, null);
  }

  private static JsonNode json(String s) {
    try {
      return M.readTree(s);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static Action action(String handler, String to) {
    return new Action("GO", "Go", List.of("R"), to, null, null, null, null, null, null, handler);
  }

  private static Step step(String code, Action a, List<Event> onExit, Map<String, FieldRule> fields) {
    return new Step(code, null, Step.Type.TASK, null, null, null, null, null, null, fields,
        a == null ? List.of() : List.of(a), List.of(), onExit, List.of(), null, null, null, List.of(), null, null);
  }

  private static Workflow wf(Step s1, List<Transition> ts) {
    return new Workflow("W", null, "E", "S1", null,
        List.of(new Status("Open", Status.Category.OPEN, null)),
        List.of(s1, step("S2", null, List.of(), Map.of()), step("S3", null, List.of(), Map.of())), ts);
  }

  private ActionStages.Prepared prepare(Action a, List<Event> onExit, List<Transition> ts,
                                        Map<String, Object> data, Map<String, Object> changes) {
    Step s1 = step("S1", a, onExit, Map.of("a", FieldRule.OPTIONAL));
    return stages.prepare(wf(s1, ts), entity, s1, a, data, changes, inst, user, base, NOW);
  }

  @Test
  void handlerRunsOnceAndItsChangesAreMergedWithRequestChanges() {
    ActionStages.Prepared p = prepare(action("setB", null), List.of(), List.of(), Map.of("n", 1),
        Map.of("a", "req"));
    assertThat(calls.get()).isEqualTo(1);
    assertThat(p.data()).containsEntry("a", "req").containsEntry("b", "from-handler").containsEntry("n", 1);
  }

  @Test
  void nullHandlerResultMeansNoChanges() {
    ActionStages.Prepared p = prepare(action("nothing", null), List.of(), List.of(), Map.of("a", "x"), Map.of());
    assertThat(p.data()).containsExactlyEntriesOf(Map.of("a", "x"));
  }

  @Test
  void unknownHandlerIsRejectedWith500() {
    assertThatThrownBy(() -> prepare(action("missing", null), List.of(), List.of(), Map.of(), Map.of()))
        .isInstanceOfSatisfying(RouterException.class, e -> {
          assertThat(e.status()).isEqualTo(500);
          assertThat(e.code()).isEqualTo("UNKNOWN_HANDLER");
        });
  }

  @Test
  void throwingHandlerIsRejectedWithoutLeakingDetail() {
    assertThatThrownBy(() -> prepare(action("boom", null), List.of(), List.of(), Map.of(), Map.of()))
        .isInstanceOfSatisfying(RouterException.class, e -> {
          assertThat(e.status()).isEqualTo(500);
          assertThat(e.code()).isEqualTo("HANDLER_FAILED");
          assertThat(e.getMessage()).doesNotContain("secret internal detail");
        });
  }

  @Test
  void invalidHandlerChangesAreRejected() {
    for (String h : List.of("ghostField", "badType")) {
      assertThatThrownBy(() -> prepare(action(h, null), List.of(), List.of(), Map.of(), Map.of()))
          .isInstanceOfSatisfying(RouterException.class, e -> {
            assertThat(e.status()).isEqualTo(500);
            assertThat(e.code()).isEqualTo("HANDLER_FAILED");
            assertThat(e.details()).isNotEmpty();
          });
    }
  }

  @Test
  void inputsAreNeverMutated() {
    Map<String, Object> data = new LinkedHashMap<>(Map.of("a", "x"));
    Map<String, Object> changes = new LinkedHashMap<>(Map.of("a", "y"));
    prepare(action("setB", null), List.of(), List.of(), data, changes);
    assertThat(data).containsExactlyEntriesOf(Map.of("a", "x"));
    assertThat(changes).containsExactlyEntriesOf(Map.of("a", "y"));
  }

  @Test
  void onExitRunsAfterHandlerAndSelectionSeesItsChanges() {
    List<Event> onExit = List.of(new Event.SetField(null, "a", json("\"routed\"")));
    Transition t = new Transition("S1", "S3", null, json("{\"==\":[{\"var\":\"data.a\"},\"routed\"]}"), 10, null);
    ActionStages.Prepared p = prepare(action("setB", "S2"), onExit, List.of(t), Map.of(), Map.of());
    assertThat(p.data()).containsEntry("a", "routed").containsEntry("b", "from-handler");
    assertThat(p.selection().targetStep()).isEqualTo("S3");
    assertThat(p.selection().transition()).isSameAs(t);
  }

  @Test
  void onExitFailuresAndSelectionErrorsAreCollectedInHistory() {
    List<Event> onExit = List.of(new Event.SetField(null, "ghost", json("1")));
    Transition bad = new Transition("S1", "S3", null, json("{\"nosuchop\":[1]}"), 10, null);
    ActionStages.Prepared p = prepare(action(null, null), onExit, List.of(bad), Map.of(), Map.of());
    assertThat(p.history()).extracting("kind").containsExactly("EVENT_FAILED", "EXPRESSION_ERROR");
    assertThat(p.selection().targetStep()).isEqualTo("S2");
  }

  @Test
  void actionToThenNextStepWhenNoTransitionMatches() {
    assertThat(prepare(action(null, "S3"), List.of(), List.of(), Map.of(), Map.of())
        .selection().targetStep()).isEqualTo("S3");
    assertThat(prepare(action(null, null), List.of(), List.of(), Map.of(), Map.of())
        .selection().targetStep()).isEqualTo("S2");
  }

  @Test
  void handlerFailureStopsBeforeOnExitRuns() {
    List<Event> onExit = List.of(new Event.HandlerCall(null, "setB"));
    assertThatThrownBy(() -> prepare(action("boom", null), onExit, List.of(), Map.of(), Map.of()))
        .isInstanceOf(RouterException.class);
    assertThat(calls.get()).isZero();
  }
}
