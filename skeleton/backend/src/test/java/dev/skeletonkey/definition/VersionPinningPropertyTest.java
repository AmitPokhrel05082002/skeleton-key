package dev.skeletonkey.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.config.HandlerRegistry;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.DefinitionCache;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Role;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Workflow;
import dev.skeletonkey.domain.route.ActionOutcome;
import dev.skeletonkey.domain.route.RouterException;
import dev.skeletonkey.persistence.DefinitionVersionRow;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.memory.InMemoryAssignmentCursorRepository;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryDefinitionRepository;
import dev.skeletonkey.persistence.memory.InMemoryInstanceRepository;
import dev.skeletonkey.persistence.memory.InMemoryUserRepository;
import dev.skeletonkey.plugin.HandlerResult;
import dev.skeletonkey.plugin.PluginHandler;
import dev.skeletonkey.record.PublishedDefinitions.PublishedApp;
import dev.skeletonkey.router.ActionStages;
import dev.skeletonkey.router.AssigneeResolver;
import dev.skeletonkey.router.EventRunner;
import dev.skeletonkey.router.Router;
import dev.skeletonkey.router.RouterGuards;
import dev.skeletonkey.router.TransitionSelector;
import dev.skeletonkey.testing.ActionSequenceArbitraries;
import dev.skeletonkey.testing.ActionSequenceArbitraries.ActionAttempt;
import dev.skeletonkey.testing.DefinitionArbitraries;
import dev.skeletonkey.testing.DefinitionArbitraries.GeneratedDefinition;
import dev.skeletonkey.web.Conflict;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

/**
 * Feature: skeleton-backend, Property 7: Published versions are immutable and instances stay pinned.
 *
 * <p>Validates: Requirements 5.2, 5.4, 5.5, 5.6, 19.3
 *
 * <p>Runs {@link DefinitionService} over the in-memory definition repository: publishes a base version, then
 * 1 to 5 later submits/edits (some published, some left DRAFT) with different workflows. Checked:
 * <ul>
 *   <li>a PUBLISHED version's document, content hash and status never change; an in-place update is a 409;
 *       an edit stores version + 1 as a DRAFT (5.2, 5.3);</li>
 *   <li>the latest published version is the highest PUBLISHED one, DRAFTs are ignored (5.4);</li>
 *   <li>an instance pinned to the base version keeps its defVersion and workflowCode across every
 *       {@code Router.execute} call (5.6, 19.3), and, routed with the workflow resolved from the version
 *       pinned on the instance after the newer versions exist, it yields the same step/status trace as on
 *       the workflow that was current when it started (5.5). Where the newer version's workflow differs,
 *       it is a different object, so resolving by "latest" would not have been the same input.</li>
 * </ul>
 *
 * <p>End-to-end pinning through WorkflowService (start pins the latest published version, loading the pinned
 * version per action) is covered by the tests of task 10.1.
 */
class VersionPinningPropertyTest {

  private static final ObjectMapper M = new ObjectMapper();
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Principal ACTOR = new Principal(new UUID(0, 7), Set.of("ADMIN"));

  /** A base definition, later documents for the same app, which of them get published, and an action sequence. */
  record Scenario(GeneratedDefinition base, List<GeneratedDefinition> later, List<Boolean> publishLater,
                  List<Boolean> viaEdit, List<ActionAttempt> attempts) {}

  @Provide
  Arbitrary<Scenario> scenarios() {
    Arbitrary<GeneratedDefinition> def = DefinitionArbitraries.validDefinition(4, 2, 10);
    return def.flatMap(base -> {
      Workflow wf = base.model().workflows().get(0);
      return net.jqwik.api.Combinators.combine(
          def.list().ofMinSize(1).ofMaxSize(5),
          Arbitraries.of(true, false).list().ofSize(5),
          Arbitraries.of(true, false).list().ofSize(5),
          ActionSequenceArbitraries.attempt(base.model(), wf).list().ofMinSize(1).ofMaxSize(20))
          .as((later, pub, edit, attempts) -> new Scenario(base, later, pub, edit, attempts));
    });
  }

  private static DefinitionService newService(InMemoryDefinitionRepository repo) {
    DefinitionParser parser = new DefinitionParser();
    return new DefinitionService(new DefinitionLoader(), repo, parser, new DefinitionCache(parser),
        DefinitionArbitraries.handlerCatalog(), new AuditService(new InMemoryAuditRepository(), Clock.systemUTC()),
        Clock.systemUTC());
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

  /** What a stored version looked like when it was published. */
  private record Snapshot(UUID id, JsonNode document, String hash, Instant publishedAt) {}

  private static String withAppCode(GeneratedDefinition g, String code) {
    ObjectNode copy = g.json().deepCopy();
    ((ObjectNode) copy.get("app")).put("code", code);
    return copy.toString();
  }

  private static void assertSnapshotsIntact(InMemoryDefinitionRepository repo, String appCode,
                                            Map<Integer, Snapshot> published) {
    for (Map.Entry<Integer, Snapshot> e : published.entrySet()) {
      DefinitionVersionRow row = repo.findByAppAndVersion(appCode, e.getKey()).orElseThrow();
      Snapshot s = e.getValue();
      assertEquals(s.id(), row.id());
      assertEquals(DefinitionVersionRow.PUBLISHED, row.status(), "published version changed status");
      assertEquals(s.document(), row.document(), "published document changed");
      assertEquals(s.hash(), row.contentHash(), "published content hash changed");
      assertEquals(s.publishedAt(), row.publishedAt(), "published time changed");
    }
  }

  /** One step/status observation per attempt; "REJECTED" for a refused call. */
  private static List<String> run(Router router, Workflow wf, Entity entity, InstanceState seed,
                                  List<ActionAttempt> attempts) {
    Principal starter = new Principal(new UUID(9, 9), Set.of("X"));
    ActionOutcome started = router.start(wf, entity, seed, new LinkedHashMap<>(), starter, T0);
    InstanceState state = started.newState();
    Map<String, Object> data = new LinkedHashMap<>(started.newData());
    List<String> trace = new ArrayList<>();
    trace.add(state.step() + "|" + state.status() + "|" + state.ended());
    for (int i = 0; i < attempts.size() && !state.ended(); i++) {
      ActionAttempt at = attempts.get(i);
      Step step = wf.step(state.step()).orElseThrow();
      String code = at.actionCode();
      Action chosen = step.actions().stream().filter(a -> a.code().equals(at.actionCode())).findFirst().orElse(null);
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
      Map<String, Object> changes = M.convertValue(at.dataChanges(), new TypeReference<LinkedHashMap<String, Object>>() {});
      try {
        ActionOutcome out = router.execute(wf, entity, state, data, code, changes, remarks, attachment, principal,
            T0.plusMillis(1500L * (i + 1)));
        InstanceState next = out.newState();
        assertEquals(seed.defVersion(), next.defVersion(), "defVersion changed");
        assertEquals(seed.workflowCode(), next.workflowCode(), "workflowCode changed");
        assertEquals(seed.appCode(), next.appCode(), "appCode changed");
        state = next;
        data = new LinkedHashMap<>(out.newData());
        trace.add(state.step() + "|" + state.status() + "|" + state.ended());
      } catch (RouterException rejected) {
        trace.add("REJECTED");
      }
    }
    return trace;
  }

  @Property(tries = 200)
  void publishedVersionsAreImmutableAndInstancesStayPinned(@ForAll("scenarios") Scenario s) {
    final String appCode = s.base().model().app().code();
    InMemoryDefinitionRepository repo = new InMemoryDefinitionRepository();
    DefinitionService service = newService(repo);

    // ---- publish the base version and start the (conceptual) instance on it
    DefinitionVersionRef v1 = service.submit(s.base().jsonText());
    service.publish(v1.id(), ACTOR);
    PublishedApp pinned = service.latestPublished(appCode).orElseThrow();
    assertEquals(1, pinned.version());
    Map<Integer, Snapshot> published = new LinkedHashMap<>();
    DefinitionVersionRow row1 = repo.findByAppAndVersion(appCode, 1).orElseThrow();
    published.put(1, new Snapshot(row1.id(), row1.document().deepCopy(), row1.contentHash(), row1.publishedAt()));

    Workflow startWf = pinned.definition().workflows().get(0);
    Entity startEntity = pinned.definition().entity(startWf.entity()).orElseThrow();
    InstanceState seed = new InstanceState(new UUID(1, 1), new UUID(2, 2), appCode, pinned.version(),
        startWf.code(), null, null, null, null, false, null, 0L, null, null);

    // ---- 1 to 5 later submits / edits, some published
    int highestPublished = 1;
    for (int i = 0; i < s.later().size(); i++) {
      int before = repo.maxVersion(appCode);
      String doc = withAppCode(s.later().get(i), appCode);
      DefinitionVersionRef draft = s.viaEdit().get(i)
          ? service.edit(appCode, highestPublished, doc)
          : service.submit(doc);
      assertEquals(before + 1, draft.version(), "new version is max + 1");
      assertEquals(DefinitionVersionRow.DRAFT, draft.status());

      // an in-place update of any PUBLISHED version is a 409 and changes nothing
      int target = published.keySet().stream().skip(i % published.size()).findFirst().orElseThrow();
      UUID targetId = published.get(target).id();
      assertThrows(Conflict.class, () -> service.update(targetId, doc));
      assertEquals(before + 1, repo.maxVersion(appCode), "rejected update stored a version");
      assertSnapshotsIntact(repo, appCode, published);

      // a DRAFT never becomes the pinned/latest version
      assertEquals(highestPublished, service.latestPublished(appCode).orElseThrow().version());

      if (s.publishLater().get(i)) {
        service.publish(draft.id(), ACTOR);
        highestPublished = draft.version();
        DefinitionVersionRow r = repo.findByAppAndVersion(appCode, draft.version()).orElseThrow();
        published.put(draft.version(), new Snapshot(r.id(), r.document().deepCopy(), r.contentHash(), r.publishedAt()));
        PublishedApp latest = service.latestPublished(appCode).orElseThrow();
        assertEquals(highestPublished, latest.version(), "new instances pin to the highest PUBLISHED version");
      }
      assertSnapshotsIntact(repo, appCode, published);
      // re-publishing a PUBLISHED version is refused and changes nothing
      UUID anyPublished = published.get(highestPublished).id();
      assertThrows(Conflict.class, () -> service.publish(anyPublished, ACTOR));
      assertSnapshotsIntact(repo, appCode, published);
    }

    // ---- pinning: resolve the workflow from the version pinned on the instance, after the newer versions exist
    DefinitionVersionRow pinnedRow = repo.findByAppAndVersion(seed.appCode(), seed.defVersion()).orElseThrow();
    AppDefinition pinnedDef = new DefinitionParser().parse(pinnedRow.document());
    Workflow resolved = pinnedDef.workflow(seed.workflowCode()).orElseThrow();
    assertEquals(startWf, resolved, "the pinned version resolves to the workflow the instance started on");
    Entity resolvedEntity = pinnedDef.entity(resolved.entity()).orElseThrow();

    List<String> baseline;
    try {
      baseline = run(newRouter(pinned.definition()), startWf, startEntity, seed, s.attempts());
    } catch (RouterException e) {
      Assume.that(false); // a generated workflow the router cannot enter is not what this property is about
      return;
    }
    List<String> afterNewerVersions = run(newRouter(pinnedDef), resolved, resolvedEntity, seed, s.attempts());
    assertEquals(baseline, afterNewerVersions, "pinned instance routed differently after newer versions");

    // the newest published version would give a different workflow: resolving by "latest" is not the same input
    PublishedApp latest = service.latestPublished(appCode).orElseThrow();
    Workflow viaLatest = latest.definition().workflows().get(0);
    boolean differs = latest.version() > 1 && !viaLatest.equals(startWf);
    Statistics.label("newer published workflow differs").collect(differs);
    if (differs) {
      assertNotEquals(startWf, viaLatest);
      assertTrue(latest.version() > seed.defVersion());
    }
    assertSnapshotsIntact(repo, appCode, published);
  }

  /** Names used in the report when the combined property fails. */
  @SuppressWarnings("unused")
  private static String names(Set<Integer> versions) {
    return versions.stream().map(String::valueOf).collect(Collectors.joining(","));
  }
}
