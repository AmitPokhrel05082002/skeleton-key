package dev.skeletonkey.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.config.HandlerRegistry;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.Assignee;
import dev.skeletonkey.domain.model.Assignee.Strategy;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Event;
import dev.skeletonkey.domain.model.Field;
import dev.skeletonkey.domain.model.HistoryEntry;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Status;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Transition;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.ActionOutcome;
import dev.skeletonkey.domain.route.RouterException;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.memory.InMemoryAssignmentCursorRepository;
import dev.skeletonkey.persistence.memory.InMemoryInstanceRepository;
import dev.skeletonkey.persistence.memory.InMemoryUserRepository;
import dev.skeletonkey.plugin.HandlerResult;
import dev.skeletonkey.plugin.PluginHandler;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Step entry loop, END handling and start (Req 8.2, 8.3, 8.6, 9.12, 10.8-10.13, 12.2, 12.3). */
class RouterEntryLoopTest {

  private static final ObjectMapper M = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");
  private static final String TRUE = "{\"==\":[1,1]}";
  private static final String BAD = "{\"nosuchop\":[1]}";

  private final UUID u1 = new UUID(0, 1);
  private final UUID u2 = new UUID(0, 2);
  private final InMemoryUserRepository users = new InMemoryUserRepository();
  private final InMemoryInstanceRepository instances = new InMemoryInstanceRepository();
  private final InMemoryAssignmentCursorRepository cursors = new InMemoryAssignmentCursorRepository();
  private final Router router;
  private final Entity entity = new Entity("E", "E", null, null,
      List.of(field("a"), field("b")));
  private final Principal user = new Principal(UUID.randomUUID(), Set.of("R"));
  private final InstanceState seed = new InstanceState(UUID.randomUUID(), UUID.randomUUID(), "APP", 1, "W",
      null, null, null, null, false, null, 0L);

  RouterEntryLoopTest() {
    users.insert(new UserAccount(u1, "u1", "x", "U", true, Set.of("R")));
    users.insert(new UserAccount(u2, "u2", "x", "U", true, Set.of("R")));
    Map<String, PluginHandler> beans = new LinkedHashMap<>();
    beans.put("setA", ctx -> HandlerResult.of(Map.of("a", "auto")));
    beans.put("boom", ctx -> {
      throw new IllegalStateException("secret");
    });
    ExpressionEngine engine = new ExpressionEngine();
    HandlerRegistry registry = new HandlerRegistry(beans);
    EventRunner events = new EventRunner(engine, registry);
    TransitionSelector selector = new TransitionSelector(engine);
    router = new Router(engine, new RouterGuards(engine), new ActionStages(registry, events, selector), selector,
        events, new AssigneeResolver(users, instances, cursors), registry);
  }

  // ---- builders ------------------------------------------------------------------------------------------------

  private static Field field(String code) {
    return new Field(code, code, Field.Type.TEXT, false, null, null, List.of(), null, null, null, null, null,
        null, null);
  }

  private static JsonNode json(String s) {
    try {
      return M.readTree(s);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static Action go(String to, String status) {
    return new Action("GO", "Go", List.of("R"), to, status, null, null, null, null, null, null);
  }

  private static Step task(String code, String status, String skipWhen, Assignee assignee, Double sla,
                           List<Event> onEnter, List<Event> onExit, Action action) {
    return new Step(code, null, Step.Type.TASK, assignee, status, skipWhen == null ? null : json(skipWhen), sla,
        null, null, Map.of(), action == null ? List.of() : List.of(action), onEnter, onExit, List.of(), null, null,
        null, List.of(), null, null);
  }

  private static Step simple(String code, String status, String skipWhen) {
    return task(code, status, skipWhen, null, null, List.of(), List.of(), go(null, null));
  }

  private static Step auto(String code, String handler, String status) {
    return new Step(code, null, Step.Type.AUTO, null, status, null, null, null, null, Map.of(), List.of(),
        List.of(), List.of(), List.of(), null, null, null, List.of(), handler, null);
  }

  private static Step end(String code, String outcome) {
    return new Step(code, null, Step.Type.END, null, null, null, null, null, null, Map.of(), List.of(),
        List.of(), List.of(), List.of(), null, null, null, List.of(), null, outcome);
  }

  private static Step unsupported(String code, Step.Type type) {
    return new Step(code, null, type, null, null, null, null, null, null, Map.of(), List.of(),
        List.of(), List.of(), List.of(), null, null, null, List.of(), null, null);
  }

  private static Transition tr(String from, String to, String status, String when) {
    return new Transition(from, to, null, when == null ? null : json(when), 100, status);
  }

  private static Workflow wf(String initial, List<Step> steps, List<Transition> ts) {
    return new Workflow("W", null, "E", steps.get(0).code(), initial,
        List.of(new Status("Open", Status.Category.OPEN, null), new Status("Other", Status.Category.OPEN, null),
            new Status("Done", Status.Category.DONE, null)),
        steps, ts);
  }

  private ActionOutcome start(Workflow w) {
    return router.start(w, entity, seed, Map.of(), user, NOW);
  }

  private ActionOutcome execute(Workflow w, InstanceState inst, Map<String, Object> data) {
    return router.execute(w, entity, inst, data, "GO", Map.of(), "ok", null, user.userId(), NOW.minusSeconds(60),
        user, NOW);
  }

  private InstanceState at(String step, String status) {
    return new InstanceState(seed.id(), seed.recordId(), "APP", 1, "W", step, status, null, null, false,
        NOW.minusSeconds(10), 3L);
  }

  private static List<String> kinds(ActionOutcome o) {
    return o.history().stream().map(HistoryEntry::kind).toList();
  }

  // ---- start ---------------------------------------------------------------------------------------------------

  @Test
  void startUsesInitialStatusWhenDeclaredElseFirstOpenAndWritesOneStartRow() {
    Workflow explicit = wf("Other", List.of(simple("S1", null, null), end("E1", "Done")), List.of());
    ActionOutcome o = start(explicit);
    assertThat(o.newState().status()).isEqualTo("Other");
    assertThat(o.newState().step()).isEqualTo("S1");
    assertThat(o.newState().ended()).isFalse();
    assertThat(o.newState().stepEnteredAt()).isEqualTo(NOW);
    assertThat(o.newState().id()).isEqualTo(seed.id());
    assertThat(o.history()).hasSize(1);
    HistoryEntry h = o.history().get(0);
    assertThat(h.kind()).isEqualTo("START");
    assertThat(h.fromStep()).isNull();
    assertThat(h.fromStatus()).isNull();
    assertThat(h.toStep()).isEqualTo("S1");
    assertThat(h.toStatus()).isEqualTo("Other");
    assertThat(h.actorId()).isEqualTo(user.userId());
    assertThat(h.eventTime()).isEqualTo(NOW);

    Workflow implicit = wf(null, List.of(simple("S1", null, null), end("E1", "Done")), List.of());
    assertThat(start(implicit).newState().status()).isEqualTo("Open");
  }

  @Test
  void startStepStatusOverridesInitialStatus() {
    Workflow w = wf("Open", List.of(simple("S1", "Other", null), end("E1", "Done")), List.of());
    assertThat(start(w).newState().status()).isEqualTo("Other");
  }

  // ---- due time and stepEnteredAt -------------------------------------------------------------------------------

  @Test
  void dueAtIsEntryTimePlusSlaHoursAndStepEnteredAtIsNow() {
    Workflow w = wf(null, List.of(task("S1", null, null, null, 1.5, List.of(), List.of(), go(null, null)),
        end("E1", "Done")), List.of());
    InstanceState s = start(w).newState();
    assertThat(s.dueAt()).isEqualTo(NOW.plusSeconds(90 * 60));
    assertThat(s.stepEnteredAt()).isEqualTo(NOW);

    Workflow noSla = wf(null, List.of(simple("S1", null, null), end("E1", "Done")), List.of());
    assertThat(start(noSla).newState().dueAt()).isNull();
  }

  // ---- skip chains ---------------------------------------------------------------------------------------------

  @Test
  void skipChainWritesSkippedRowsAndEntersFirstUnskippedStep() {
    Workflow w = wf(null, List.of(simple("S1", null, TRUE), simple("S2", "Other", TRUE), simple("S3", null, null),
        end("E1", "Done")), List.of());
    ActionOutcome o = start(w);
    assertThat(o.newState().step()).isEqualTo("S3");
    // statuses of skipped steps are never applied
    assertThat(o.newState().status()).isEqualTo("Open");
    assertThat(kinds(o)).containsExactly("START", "SKIPPED", "SKIPPED");
    HistoryEntry first = o.history().get(1);
    assertThat(first.fromStep()).isEqualTo("S1");
    assertThat(first.toStep()).isEqualTo("S2");
    assertThat(first.fromStatus()).isEqualTo("Open");
    assertThat(first.toStatus()).isEqualTo("Open");
    assertThat(first.actorId()).isEqualTo(user.userId());
    assertThat(o.history().get(2).fromStep()).isEqualTo("S2");
    assertThat(o.history().get(2).toStep()).isEqualTo("S3");
  }

  @Test
  void skipLoopIsRejectedWith500() {
    Workflow w = wf(null, List.of(simple("S1", null, TRUE), simple("S2", null, TRUE), end("E1", "Done")),
        List.of(tr("S2", "S1", null, null)));
    assertThatThrownBy(() -> start(w)).isInstanceOfSatisfying(RouterException.class, e -> {
      assertThat(e.status()).isEqualTo(500);
      assertThat(e.code()).isEqualTo(Router.SKIP_LOOP);
    });
  }

  @Test
  void skipWhenErrorCountsAsFalseAndIsRecorded() {
    Workflow w = wf(null, List.of(simple("S1", null, BAD), end("E1", "Done")), List.of());
    ActionOutcome o = start(w);
    assertThat(o.newState().step()).isEqualTo("S1");
    assertThat(kinds(o)).containsExactly("START", "EXPRESSION_ERROR");
    HistoryEntry err = o.history().get(1);
    assertThat(err.fromStep()).isEqualTo("S1");
    assertThat(err.detail().get("step").asText()).isEqualTo("S1");
    assertThat(err.detail().get("error").asText()).isNotBlank();
  }

  // ---- AUTO ----------------------------------------------------------------------------------------------------

  @Test
  void autoStepsRunHandlerAndRouteOnImmediately() {
    Workflow w = wf(null, List.of(auto("S1", "setA", "Other"), auto("S2", null, null), simple("S3", null, null),
        end("E1", "Done")), List.of());
    ActionOutcome o = start(w);
    assertThat(o.newState().step()).isEqualTo("S3");
    assertThat(o.newState().status()).isEqualTo("Other");
    assertThat(o.newData()).containsEntry("a", "auto");
    assertThat(kinds(o)).containsExactly("START");
  }

  @Test
  void failingOrUnknownAutoHandlerIsRejectedWith500() {
    for (String h : List.of("boom", "ghost")) {
      Workflow w = wf(null, List.of(auto("S1", h, null), simple("S2", null, null), end("E1", "Done")), List.of());
      assertThatThrownBy(() -> start(w)).isInstanceOfSatisfying(RouterException.class,
          e -> assertThat(e.status()).isEqualTo(500));
    }
  }

  // ---- END -----------------------------------------------------------------------------------------------------

  @Test
  void endSetsOutcomeClosesInstanceAndClearsAssigneeAndDue() {
    Workflow w = wf(null, List.of(task("S1", null, null, null, 2.0, List.of(), List.of(), go("E1", null)),
        end("E1", "Done")), List.of());
    InstanceState inst = new InstanceState(seed.id(), seed.recordId(), "APP", 1, "W", "S1", "Open", null,
        NOW.plusSeconds(100), false, NOW.minusSeconds(10), 3L);
    ActionOutcome o = execute(w, inst, Map.of("a", "x"));
    assertThat(o.newState().ended()).isTrue();
    assertThat(o.newState().step()).isEqualTo("E1");
    assertThat(o.newState().status()).isEqualTo("Done");
    assertThat(o.newState().assigneeId()).isNull();
    assertThat(o.newState().dueAt()).isNull();
    assertThat(o.newState().lockVersion()).isEqualTo(3L);
    assertThat(kinds(o)).containsExactly("ACTION");
    HistoryEntry a = o.history().get(0);
    assertThat(a.actionCode()).isEqualTo("GO");
    assertThat(a.fromStep()).isEqualTo("S1");
    assertThat(a.toStep()).isEqualTo("E1");
    assertThat(a.fromStatus()).isEqualTo("Open");
    assertThat(a.toStatus()).isEqualTo("Done");
    assertThat(a.remarks()).isEqualTo("ok");
    assertThat(a.timeInStepMs()).isEqualTo(10_000L);
  }

  @Test
  void startAtEndStepClosesImmediately() {
    Workflow w = wf(null, List.of(end("E1", "Done"), end("E2", "Done")), List.of());
    ActionOutcome o = start(w);
    assertThat(o.newState().ended()).isTrue();
    assertThat(o.newState().status()).isEqualTo("Done");
  }

  @Test
  void parallelJoinAndSubworkflowStepsFailClearly() {
    for (Step.Type t : List.of(Step.Type.PARALLEL, Step.Type.JOIN, Step.Type.SUBWORKFLOW)) {
      Workflow w = wf(null, List.of(unsupported("S1", t), end("E1", "Done")), List.of());
      assertThatThrownBy(() -> start(w)).isInstanceOfSatisfying(RouterException.class, e -> {
        assertThat(e.status()).isEqualTo(500);
        assertThat(e.code()).isEqualTo(Router.UNSUPPORTED_STEP);
      });
    }
  }

  // ---- status precedence ---------------------------------------------------------------------------------------

  @Test
  void statusPrecedenceAcrossSkipsUsesTransitionOfLastMoveThenFinalStep() {
    List<Step> steps = List.of(simple("S1", null, null), simple("S2", "Other", TRUE), simple("S3", "Done", null),
        end("E1", "Done"));
    InstanceState inst = at("S1", "Open");

    // the action's status applies only to its own move; the skipped hop carries no action
    Workflow noTransition = wf(null, steps.subList(0, 4), List.of());
    ActionOutcome a = router.execute(noTransition, entity, inst, Map.of(), "GO", Map.of(), null, null, null, NOW,
        user, NOW);
    assertThat(a.newState().step()).isEqualTo("S3");
    assertThat(a.newState().status()).isEqualTo("Done");

    // a transition on the last move wins over the final step's status
    Workflow withTransition = wf(null, steps, List.of(tr("S2", "S3", "Other", null)));
    ActionOutcome b = execute(withTransition, inst, Map.of());
    assertThat(b.newState().step()).isEqualTo("S3");
    assertThat(b.newState().status()).isEqualTo("Other");
  }

  @Test
  void actionStatusAppliesWhenNothingIsSkippedAndTransitionStatusBeatsIt() {
    Step s1 = task("S1", null, null, null, null, List.of(), List.of(), go("S2", "Other"));
    Workflow w = wf(null, List.of(s1, simple("S2", "Done", null), end("E1", "Done")), List.of());
    // action status beats the target step status
    assertThat(execute(w, at("S1", "Open"), Map.of()).newState().status()).isEqualTo("Other");

    Workflow t = wf(null, List.of(s1, simple("S2", "Done", null), end("E1", "Done")),
        List.of(tr("S1", "S2", "Open", null)));
    assertThat(execute(t, at("S1", "Open"), Map.of()).newState().status()).isEqualTo("Open");
  }

  @Test
  void statusIsKeptWhenNobodySetsOne() {
    Workflow w = wf(null, List.of(task("S1", null, null, null, null, List.of(), List.of(), go("S2", null)),
        simple("S2", null, null), end("E1", "Done")), List.of());
    assertThat(execute(w, at("S1", "Other"), Map.of()).newState().status()).isEqualTo("Other");
  }

  // ---- assignment ----------------------------------------------------------------------------------------------

  @Test
  void roundRobinCursorUpdateIsReturnedNotWritten() {
    Assignee rr = new Assignee(Strategy.ROUND_ROBIN, "R", null);
    Workflow w = wf(null, List.of(task("S1", null, null, rr, null, List.of(), List.of(), go(null, null)),
        end("E1", "Done")), List.of());
    ActionOutcome o = start(w);
    assertThat(o.newState().assigneeId()).isEqualTo(u1);
    assertThat(o.cursorUpdates()).hasSize(1);
    assertThat(o.cursorUpdates().get(0).lastUserId()).isEqualTo(u1);
    assertThat(o.cursorUpdates().get(0).stepCode()).isEqualTo("S1");
    assertThat(cursors.findLastUser("APP", "W", "S1")).isEmpty();
  }

  @Test
  void rejectedLoopLeavesCursorsUntouched() {
    Assignee rr = new Assignee(Strategy.ROUND_ROBIN, "R", null);
    Workflow w = wf(null, List.of(task("S0", null, null, rr, null, List.of(), List.of(), go("S1", null)),
        simple("S1", null, TRUE), simple("S2", null, TRUE), end("E1", "Done")),
        List.of(tr("S2", "S1", null, null)));
    assertThatThrownBy(() -> execute(w, at("S0", "Open"), Map.of())).isInstanceOf(RouterException.class);
    assertThat(cursors.findLastUser("APP", "W", "S0")).isEmpty();
  }

  @Test
  void initiatorStrategyAssignsTheSuppliedInitiator() {
    Assignee init = new Assignee(Strategy.INITIATOR, null, null);
    Workflow w = wf(null, List.of(task("S1", null, null, null, null, List.of(), List.of(), go("S2", null)),
        task("S2", null, null, init, null, List.of(), List.of(), go(null, null)), end("E1", "Done")), List.of());
    UUID starter = UUID.randomUUID();
    ActionOutcome o = router.execute(w, entity, at("S1", "Open"), Map.of(), "GO", Map.of(), null, null, starter,
        NOW, user, NOW);
    assertThat(o.newState().assigneeId()).isEqualTo(starter);
  }

  // ---- history order -------------------------------------------------------------------------------------------

  @Test
  void historyOrderIsErrorsAndEventsThenActionThenSkippedThenEntryEntries() {
    JsonNode ghost = json("1");
    List<Event> onExit = List.of(new Event.SetField(null, "ghost", ghost));
    List<Event> onEnterS3 = List.of(new Event.SetField(null, "ghost", ghost));
    Step s1 = task("S1", null, null, null, null, List.of(), onExit, go("S2", null));
    Step s3 = task("S3", null, null, null, null, onEnterS3, List.of(), go(null, null));
    Workflow w = wf(null, List.of(s1, simple("S2", null, TRUE), s3, end("E1", "Done")),
        List.of(tr("S1", "S2", null, BAD)));
    ActionOutcome o = execute(w, at("S1", "Open"), Map.of());
    assertThat(o.newState().step()).isEqualTo("S3");
    assertThat(kinds(o)).containsExactly("EVENT_FAILED", "EXPRESSION_ERROR", "ACTION", "SKIPPED", "EVENT_FAILED");
    assertThat(o.history().get(2).toStep()).isEqualTo("S3");
  }

  @Test
  void skipWhenErrorOnEntryComesAfterActionRow() {
    Workflow w = wf(null, List.of(simple("S1", null, null), simple("S2", null, BAD), end("E1", "Done")), List.of());
    ActionOutcome o = execute(w, at("S1", "Open"), Map.of());
    assertThat(o.newState().step()).isEqualTo("S2");
    assertThat(kinds(o)).containsExactly("ACTION", "EXPRESSION_ERROR");
  }

  @Test
  void onEnterEventsChangeDataSeenByTheOutcome() {
    List<Event> onEnter = List.of(new Event.SetField(null, "b", json("\"set\"")));
    Workflow w = wf(null, List.of(task("S1", null, null, null, null, onEnter, List.of(), go(null, null)),
        end("E1", "Done")), List.of());
    assertThat(start(w).newData()).containsEntry("b", "set");
  }
}
