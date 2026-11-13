package dev.skeletonkey.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.config.HandlerRegistry;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.Assignee;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Field;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Status;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Step.FieldRule;
import dev.skeletonkey.domain.model.Transition;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.RouterException;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.memory.InMemoryAssignmentCursorRepository;
import dev.skeletonkey.persistence.memory.InMemoryInstanceRepository;
import dev.skeletonkey.persistence.memory.InMemoryUserRepository;
import dev.skeletonkey.plugin.HandlerResult;
import dev.skeletonkey.plugin.PluginHandler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;

/**
 * Feature: skeleton-backend, Property 13: Rejected requests change nothing.
 *
 * <p>Every request that must be rejected throws a {@link RouterException} with the expected status and code, leaves
 * the input data, changes and instance state equal to their values before the call, writes no assignment cursor and
 * lets no handler side effect escape.
 *
 * <p><b>Validates: Requirements 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 9.11, 9.14, 10.7, 10.10, 10.12, 10.15,
 * 11.4, 11.5, 12.4, 13.4, 13.6, 13.7, 19.4</b>
 */
class RejectedRequestsPropertyTest {

  private static final ObjectMapper M = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");
  private static final String TRUE = "{\"==\":[1,1]}";
  private static final String FALSE = "{\"==\":[1,2]}";
  private static final String BAD = "{\"nosuchop\":[1]}";

  enum Reason {
    ENDED, UNKNOWN_ACTION, MISSING_ROLE, WHEN_FALSE, WHEN_ERROR, NOT_ASSIGNEE, READONLY_WRITE, UNLISTED_WRITE,
    REMARKS_REQUIRED, ATTACHMENT_REQUIRED, WRONG_TYPE, TOO_LONG, REQUIRED_CLEARED, REQUIRED_ABSENT,
    UNKNOWN_HANDLER, HANDLER_THROWS, HANDLER_INVALID_TYPE, HANDLER_UNKNOWN_FIELD, SKIP_LOOP, NO_NEXT_STEP
  }

  /** A fully built rejected request plus the verdict it must produce. */
  private record Case(Workflow wf, InstanceState inst, Map<String, Object> data, String actionCode,
                      Map<String, Object> changes, String remarks, String attachment, Principal principal,
                      int status, String code, boolean handlerRuns) {}

  private final InMemoryUserRepository users = new InMemoryUserRepository();
  private final InMemoryInstanceRepository instances = new InMemoryInstanceRepository();
  private final InMemoryAssignmentCursorRepository cursors = new InMemoryAssignmentCursorRepository();
  private final AtomicInteger handlerCalls = new AtomicInteger();
  private final List<Object> leaked = new ArrayList<>();
  private final Router router;

  private final Entity entity = new Entity("E", "E", null, null, List.of(
      field("t", Field.Type.TEXT, 5), field("n", Field.Type.NUMBER, null), field("req", Field.Type.TEXT, null),
      field("ro", Field.Type.TEXT, null), field("h", Field.Type.TEXT, null)));

  RejectedRequestsPropertyTest() {
    users.insert(new UserAccount(new UUID(0, 1), "u1", "x", "U", true, Set.of("R")));
    users.insert(new UserAccount(new UUID(0, 2), "u2", "x", "U", true, Set.of("R")));
    Map<String, PluginHandler> beans = new LinkedHashMap<>();
    beans.put("boom", ctx -> {
      handlerCalls.incrementAndGet();
      tryMutate(ctx.data());
      throw new IllegalStateException("secret internal detail");
    });
    beans.put("badType", ctx -> {
      handlerCalls.incrementAndGet();
      tryMutate(ctx.data());
      return HandlerResult.of(Map.of("n", "not-a-number"));
    });
    beans.put("badField", ctx -> {
      handlerCalls.incrementAndGet();
      tryMutate(ctx.data());
      return HandlerResult.of(Map.of("zz", 1));
    });
    ExpressionEngine engine = new ExpressionEngine();
    HandlerRegistry registry = new HandlerRegistry(beans);
    EventRunner events = new EventRunner(engine, registry);
    TransitionSelector selector = new TransitionSelector(engine);
    router = new Router(engine, new RouterGuards(engine), new ActionStages(registry, events, selector), selector,
        events, new AssigneeResolver(users, instances, cursors), registry);
  }

  /** A handler trying to leak a write into the caller's map; an immutable map simply refuses. */
  private void tryMutate(Map<String, Object> data) {
    try {
      data.put("leak", "x");
      leaked.add("leak");
    } catch (RuntimeException expected) {
      // immutable view: nothing leaks
    }
  }

  // ---- builders ------------------------------------------------------------------------------------------------

  private static Field field(String code, Field.Type type, Integer maxLength) {
    return new Field(code, code, type, false, null, null, List.of(), null, null, null, maxLength, null, null, null);
  }

  private static JsonNode json(String s) {
    try {
      return M.readTree(s);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static Action act(String code, String to, List<String> roles, Action.Presence remarks,
                            Action.Presence attachment, String when, String handler) {
    return new Action(code, code, roles, to, null, remarks, attachment, null, null,
        when == null ? null : json(when), handler);
  }

  private static Step step(String code, Step.Type type, String skipWhen, Map<String, FieldRule> fields,
                           List<Action> actions, String outcome) {
    Assignee queue = type == Step.Type.TASK ? new Assignee(Assignee.Strategy.ROLE_QUEUE, "R", null) : null;
    return new Step(code, null, type, queue, null, skipWhen == null ? null : json(skipWhen), null, null, null,
        fields, actions, List.of(), List.of(), List.of(), null, null, null, List.of(), null, outcome);
  }

  private static Workflow workflow() {
    List<String> r = List.of("R");
    Map<String, FieldRule> rules = new LinkedHashMap<>();
    rules.put("t", FieldRule.OPTIONAL);
    rules.put("n", FieldRule.OPTIONAL);
    rules.put("req", FieldRule.REQUIRED);
    rules.put("ro", FieldRule.READONLY);
    List<Action> actions = List.of(
        act("GO", "S2", r, null, null, null, null),
        act("REM", "S2", r, Action.Presence.REQUIRED, null, null, null),
        act("ATT", "S2", r, null, Action.Presence.REQUIRED, null, null),
        act("ROLEX", "S2", List.of("X"), null, null, null, null),
        act("WFALSE", "S2", r, null, null, FALSE, null),
        act("WERR", "S2", r, null, null, BAD, null),
        act("HGHOST", "S2", r, null, null, null, "ghost"),
        act("HBOOM", "S2", r, null, null, null, "boom"),
        act("HTYPE", "S2", r, null, null, null, "badType"),
        act("HFIELD", "S2", r, null, null, null, "badField"),
        act("LOOP", "S3", r, null, null, null, null));
    List<Step> steps = List.of(
        step("S1", Step.Type.TASK, null, rules, actions, null),
        step("S2", Step.Type.TASK, null, Map.of(), List.of(act("GO", null, r, null, null, null, null)), null),
        step("S3", Step.Type.TASK, TRUE, Map.of(), List.of(act("GO", null, r, null, null, null, null)), null),
        step("S4", Step.Type.TASK, TRUE, Map.of(), List.of(act("GO", null, r, null, null, null, null)), null),
        step("E1", Step.Type.END, null, Map.of(), List.of(), "Done"),
        step("S9", Step.Type.TASK, null, Map.of(), List.of(act("GO", null, r, null, null, null, null)), null));
    return new Workflow("W", null, "E", "S1", null,
        List.of(new Status("Open", Status.Category.OPEN, null), new Status("Done", Status.Category.DONE, null)),
        steps, List.of(new Transition("S4", "S3", null, null, 100, null)));
  }

  private static InstanceState instance(String step, boolean ended, UUID assignee, UUID starter) {
    return new InstanceState(new UUID(5, 5), new UUID(6, 6), "APP", 1, "W", step, "Open", assignee,
        NOW.plusSeconds(3600), ended, NOW.minusSeconds(30), 4L, starter, NOW.minusSeconds(600));
  }

  private static Map<String, Object> goodData(String t, int n) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("t", t);
    d.put("n", n);
    d.put("req", "filled");
    d.put("ro", "fixed");
    return d;
  }

  private static UUID other(UUID id, long hi, long lo) {
    UUID c = new UUID(hi, lo);
    return c.equals(id) ? new UUID(hi + 1, lo) : c;
  }

  /** Builds the rejected request for {@code reason}; all free values come from the generators. */
  private Case build(Reason reason, String word, int num, boolean variant, long hi, long lo) {
    Workflow wf = workflow();
    UUID me = other(null, hi, lo);
    UUID stranger = other(me, lo, hi);
    Principal user = new Principal(me, variant ? Set.of("R") : Set.of("R", "Y"));
    String shortText = word.length() > 5 ? word.substring(0, 5) : word;
    Map<String, Object> data = goodData(shortText, num);
    UUID assignee = variant ? me : null;
    InstanceState inst = instance("S1", false, assignee, me);
    String action = "GO";
    Map<String, Object> changes = new LinkedHashMap<>();
    String remarks = variant ? "ok " + word : null;
    String attachment = null;
    int status;
    String code;
    boolean handler = false;

    switch (reason) {
      case ENDED -> {
        inst = instance(variant ? "E1" : "S1", true, assignee, me);
        action = variant ? "GO" : "ZZ" + word;
        status = 409;
        code = "INSTANCE_ENDED";
      }
      case UNKNOWN_ACTION -> {
        action = "ZZ" + word;
        status = 409;
        code = "ACTION_NOT_AVAILABLE";
      }
      case MISSING_ROLE -> {
        action = "ROLEX";
        status = 403;
        code = "INSUFFICIENT_ROLE";
      }
      case WHEN_FALSE -> {
        action = "WFALSE";
        status = 409;
        code = "ACTION_CONDITION_NOT_MET";
      }
      case WHEN_ERROR -> {
        action = "WERR";
        status = 409;
        code = "ACTION_CONDITION_NOT_MET";
      }
      case NOT_ASSIGNEE -> {
        inst = instance("S1", false, stranger, me);
        status = 403;
        code = "NOT_ASSIGNEE";
      }
      case READONLY_WRITE -> {
        changes.put("ro", word);
        status = 403;
        code = "FIELD_NOT_WRITABLE";
      }
      case UNLISTED_WRITE -> {
        changes.put("h", word);
        status = 403;
        code = "FIELD_NOT_WRITABLE";
      }
      case REMARKS_REQUIRED -> {
        action = "REM";
        remarks = variant ? null : "   ";
        status = 422;
        code = "REMARKS_REQUIRED";
      }
      case ATTACHMENT_REQUIRED -> {
        action = "ATT";
        attachment = variant ? null : "  ";
        status = 422;
        code = "ATTACHMENT_REQUIRED";
      }
      case WRONG_TYPE -> {
        if (variant) {
          changes.put("n", "x" + word);
        } else {
          changes.put("t", num);
        }
        status = 422;
        code = "VALIDATION_FAILED";
      }
      case TOO_LONG -> {
        changes.put("t", "abcdef" + word);
        status = 422;
        code = "VALIDATION_FAILED";
      }
      case REQUIRED_CLEARED -> {
        changes.put("req", variant ? null : "   ");
        status = 422;
        code = "REQUIRED_FIELDS_MISSING";
      }
      case REQUIRED_ABSENT -> {
        data.remove("req");
        status = 422;
        code = "REQUIRED_FIELDS_MISSING";
      }
      case UNKNOWN_HANDLER -> {
        action = "HGHOST";
        status = 500;
        code = ActionStages.UNKNOWN_HANDLER;
      }
      case HANDLER_THROWS -> {
        action = "HBOOM";
        handler = true;
        status = 500;
        code = ActionStages.HANDLER_FAILED;
      }
      case HANDLER_INVALID_TYPE -> {
        action = "HTYPE";
        handler = true;
        status = 500;
        code = ActionStages.HANDLER_FAILED;
      }
      case HANDLER_UNKNOWN_FIELD -> {
        action = "HFIELD";
        handler = true;
        status = 500;
        code = ActionStages.HANDLER_FAILED;
      }
      case SKIP_LOOP -> {
        action = "LOOP";
        status = 500;
        code = Router.SKIP_LOOP;
      }
      case NO_NEXT_STEP -> {
        inst = instance("S9", false, assignee, me);
        status = 422;
        code = "NO_NEXT_STEP";
      }
      default -> throw new IllegalStateException();
    }
    return new Case(wf, inst, data, action, changes, remarks, attachment, user, status, code, handler);
  }

  // ---- verification --------------------------------------------------------------------------------------------

  private void assertRejectedWithoutTrace(Case c, Reason reason) {
    Map<String, Object> dataBefore = new LinkedHashMap<>(c.data());
    Map<String, Object> changesBefore = new LinkedHashMap<>(c.changes());
    InstanceState instBefore = new InstanceState(c.inst().id(), c.inst().recordId(), c.inst().appCode(),
        c.inst().defVersion(), c.inst().workflowCode(), c.inst().step(), c.inst().status(), c.inst().assigneeId(),
        c.inst().dueAt(), c.inst().ended(), c.inst().stepEnteredAt(), c.inst().lockVersion(),
        c.inst().startedBy(), c.inst().startedAt());
    int callsBefore = handlerCalls.get();

    try {
      router.execute(c.wf(), entity, c.inst(), c.data(), c.actionCode(), c.changes(), c.remarks(), c.attachment(),
          c.principal(), NOW);
      fail("expected rejection " + reason + " but the request was accepted");
    } catch (RouterException e) {
      assertEquals(c.status(), e.status(), reason + " status (" + e.code() + ": " + e.getMessage() + ")");
      assertEquals(c.code(), e.code(), reason + " code");
      assertTrue(e.status() < 500 || !String.valueOf(e.getMessage()).contains("secret"),
          "5xx message must not leak handler detail");
    }

    assertEquals(dataBefore, c.data(), reason + ": input data changed");
    assertEquals(changesBefore, c.changes(), reason + ": request changes changed");
    assertEquals(instBefore, c.inst(), reason + ": instance state changed");
    assertTrue(cursors.findLastUser("APP", "W", "S1").isEmpty(), "cursor S1 written");
    assertTrue(cursors.findLastUser("APP", "W", "S2").isEmpty(), "cursor S2 written");
    assertTrue(cursors.findLastUser("APP", "W", "S3").isEmpty(), "cursor S3 written");
    assertTrue(leaked.isEmpty(), "handler side effect leaked into caller data");
    int calls = handlerCalls.get() - callsBefore;
    assertEquals(c.handlerRuns() ? 1 : 0, calls, reason + ": handler call count");
  }

  @Property(tries = 300)
  void rejectedRequestsChangeNothing(@ForAll Reason reason,
                                     @ForAll @AlphaChars @StringLength(min = 1, max = 8) String word,
                                     @ForAll int num, @ForAll boolean variant, @ForAll long hi,
                                     @ForAll long lo) {
    assertRejectedWithoutTrace(build(reason, word, num, variant, hi, lo), reason);
  }

  /** Guarantees every rejection reason (both variants) is exercised, whatever the random draw. */
  @Example
  void everyRejectionReasonIsCovered() {
    for (Reason reason : Reason.values()) {
      for (boolean variant : new boolean[] {true, false}) {
        assertRejectedWithoutTrace(build(reason, "abc", 7, variant, 11L, 13L), reason);
      }
    }
  }
}
