package dev.skeletonkey.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.config.HandlerRegistry;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.model.HistoryEntry;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.route.CursorUpdate;
import dev.skeletonkey.domain.route.RouterException;
import dev.skeletonkey.persistence.AuditEntry;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.memory.InMemoryAssignmentCursorRepository;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryHistoryRepository;
import dev.skeletonkey.persistence.memory.InMemoryInstanceRepository;
import dev.skeletonkey.persistence.memory.InMemoryRecordRepository;
import dev.skeletonkey.persistence.memory.InMemoryReferenceCounter;
import dev.skeletonkey.persistence.memory.InMemoryUserRepository;
import dev.skeletonkey.record.PublishedDefinitions;
import dev.skeletonkey.record.PublishedDefinitions.PublishedApp;
import dev.skeletonkey.router.ActionStages;
import dev.skeletonkey.router.AssigneeResolver;
import dev.skeletonkey.router.EventRunner;
import dev.skeletonkey.router.Router;
import dev.skeletonkey.router.RouterGuards;
import dev.skeletonkey.router.TransitionSelector;
import dev.skeletonkey.web.Conflict;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/** WorkflowService on the in-memory twins (Req 8.1, 8.7, 9.12-9.14, 12.4, 5.4, 5.5). */
class WorkflowServiceTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);

    private final UUID clerkId = new UUID(0, 1);
    private final UUID inspectorId = new UUID(0, 2);
    private final Principal clerk = new Principal(clerkId, Set.of("CLERK"));
    private final Principal officer = new Principal(new UUID(0, 3), Set.of("OFFICER"));
    private final Principal citizen = Principal.anonymous("PUBLIC");

    private final List<String> log = new ArrayList<>();
    private boolean failHistory;
    private boolean staleReads;
    private final TreeMap<Integer, AppDefinition> versions = new TreeMap<>();

    private InMemoryRecordRepository records;
    private InMemoryInstanceRepository instances;
    private InMemoryHistoryRepository history;
    private InMemoryAssignmentCursorRepository cursors;
    private InMemoryAuditRepository auditRepo;
    private WorkflowService service;

    private static JsonNode grievanceJson() throws Exception {
        return M.readTree(Files.readString(Path.of("../../examples/grievance.app.json")));
    }

    @BeforeEach
    void setUp() throws Exception {
        versions.put(1, new DefinitionParser().parse(grievanceJson()));

        PublishedDefinitions defs = new PublishedDefinitions() {
            @Override
            public Optional<PublishedApp> latestPublished(String appCode) {
                return versions.isEmpty() ? Optional.empty()
                        : Optional.of(new PublishedApp(versions.lastKey(), versions.lastEntry().getValue()));
            }

            @Override
            public Optional<PublishedApp> publishedVersion(String appCode, int version) {
                return Optional.ofNullable(versions.get(version)).map(d -> new PublishedApp(version, d));
            }
        };

        records = new InMemoryRecordRepository() {
            @Override
            public void insert(dev.skeletonkey.persistence.RecordRow row) {
                log.add("record.insert");
                super.insert(row);
            }

            @Override
            public boolean updateData(UUID id, Map<String, Object> data, Instant at) {
                log.add("record.update");
                return super.updateData(id, data, at);
            }
        };
        instances = new InMemoryInstanceRepository() {
            @Override
            public void insert(InstanceState s) {
                log.add("instance.insert");
                super.insert(s);
            }

            @Override
            public Optional<InstanceState> findById(UUID id) {
                Optional<InstanceState> found = super.findById(id);
                return staleReads ? found.map(s -> s.withLockVersion(s.lockVersion() - 1)) : found;
            }

            @Override
            public InstanceState update(InstanceState s) {
                log.add("instance.update");
                return super.update(s);
            }
        };
        history = new InMemoryHistoryRepository() {
            @Override
            public synchronized void append(UUID instanceId, HistoryEntry entry) {
                if (failHistory) {
                    throw new IllegalStateException("disk full");
                }
                log.add("history." + entry.kind());
                super.append(instanceId, entry);
            }
        };
        cursors = new InMemoryAssignmentCursorRepository() {
            @Override
            public void apply(CursorUpdate u) {
                log.add("cursor.apply");
                super.apply(u);
            }
        };
        auditRepo = new InMemoryAuditRepository() {
            @Override
            public synchronized void append(AuditEntry entry) {
                log.add("audit." + entry.action());
                super.append(entry);
            }
        };

        InMemoryUserRepository users = new InMemoryUserRepository();
        users.insert(new UserAccount(clerkId, "clerk", "x", "Clerk", true, Set.of("CLERK")));
        users.insert(new UserAccount(inspectorId, "insp", "x", "Inspector", true, Set.of("INSPECTOR")));

        ExpressionEngine engine = new ExpressionEngine();
        HandlerRegistry registry = new HandlerRegistry(Map.of());
        EventRunner events = new EventRunner(engine, registry);
        TransitionSelector selector = new TransitionSelector(engine);
        Router router = new Router(engine, new RouterGuards(engine), new ActionStages(registry, events, selector),
                selector, events, new AssigneeResolver(users, instances, cursors), registry);
        service = new WorkflowService(defs, records, instances, history, cursors, new InMemoryReferenceCounter(),
                new AuditService(auditRepo, CLOCK), router, CLOCK);
    }

    private static Map<String, Object> payload(boolean urgent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", "Pothole");
        m.put("details", "Deep one");
        m.put("category", "ROAD");
        m.put("contactEmail", "a@b.co");
        m.put("urgent", urgent);
        return m;
    }

    private WorkflowService.Started startOne() {
        return service.start("GRIEVANCE", "GRIEVANCE_FLOW", payload(false), citizen);
    }

    private WorkflowService.Executed verify(UUID id) {
        return service.execute(id, "VERIFY", Map.of(), "ok", null, clerk);
    }

    @Test
    void startPersistsRecordInstanceHistoryAuditInOrderAndPinsLatestVersion() {
        versions.put(2, versions.get(1));
        WorkflowService.Started s = startOne();

        assertThat(log).containsExactly("record.insert", "instance.insert", "history.START", "audit.create");
        assertThat(s.instance().defVersion()).isEqualTo(2);
        assertThat(s.record().defVersion()).isEqualTo(2);
        assertThat(s.record().referenceNo()).isEqualTo("GRV-2026-000001");
        assertThat(s.instance().step()).isEqualTo("TRIAGE");
        assertThat(s.instance().assigneeId()).isEqualTo(clerkId);
        assertThat(instances.findById(s.instance().id())).isPresent();
        assertThat(history.findByInstance(s.instance().id())).extracting(HistoryEntry::kind).containsExactly("START");
    }

    @Test
    void startRejectsUnpublishedUnknownWorkflowAndInvalidPayloadWithoutWrites() {
        assertThatThrownBy(() -> service.start("GRIEVANCE", "NOPE", payload(false), citizen))
                .isInstanceOf(dev.skeletonkey.web.NotFound.class);
        Map<String, Object> bad = payload(false);
        bad.put("bogus", 1);
        assertThatThrownBy(() -> service.start("GRIEVANCE", "GRIEVANCE_FLOW", bad, citizen))
                .isInstanceOf(dev.skeletonkey.web.ValidationFailed.class);
        versions.clear();
        assertThatThrownBy(() -> service.start("GRIEVANCE", "GRIEVANCE_FLOW", payload(false), citizen))
                .isInstanceOf(Conflict.class);
        assertThat(log).isEmpty();
    }

    @Test
    void executeWritesRecordInstanceHistoryCursorAuditInOrder() {
        WorkflowService.Started s = startOne();
        verify(s.instance().id());
        log.clear();

        // REVIEW -> SITE_VISIT (ROUND_ROBIN inspector) with a data change: all five write kinds occur.
        WorkflowService.Executed e = service.execute(s.instance().id(), "APPROVE",
                Map.of("resolutionNotes", "will fix"), null, null, officer);

        assertThat(log).containsExactly("record.update", "instance.update", "history.ACTION", "cursor.apply",
                "audit.update");
        assertThat(e.instance().step()).isEqualTo("SITE_VISIT");
        assertThat(e.instance().assigneeId()).isEqualTo(inspectorId);
        assertThat(e.instance().lockVersion()).isEqualTo(2L);
        assertThat(cursors.findLastUser("GRIEVANCE", "GRIEVANCE_FLOW", "SITE_VISIT")).contains(inspectorId);
        assertThat(records.findById(s.record().id()).orElseThrow().data()).containsEntry("resolutionNotes", "will fix");
        assertThat(auditRepo.findByTarget("record", s.record().id().toString())).extracting(AuditEntry::action)
                .containsExactly("create", "update");
    }

    @Test
    void historyFailureMapsTo500AndNothingAfterItIsWritten() {
        WorkflowService.Started s = startOne();
        verify(s.instance().id());
        log.clear();
        failHistory = true;

        assertThatThrownBy(() -> service.execute(s.instance().id(), "APPROVE",
                Map.of("resolutionNotes", "x"), null, null, officer))
                .isInstanceOfSatisfying(RouterException.class, e -> {
                    assertThat(e.status()).isEqualTo(500);
                    assertThat(e.code()).isEqualTo("HISTORY_WRITE_FAILED");
                    assertThat(e.getMessage()).doesNotContain("disk");
                    assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
                });

        // Cursor and audit come after history in the persistence order, so they were never reached.
        assertThat(log).doesNotContain("cursor.apply", "audit.update");
        assertThat(cursors.findLastUser("GRIEVANCE", "GRIEVANCE_FLOW", "SITE_VISIT")).isEmpty();
        assertThat(auditRepo.findByTarget("record", s.record().id().toString())).hasSize(1);
        // The twins have no rollback; atomicity is carried by @Transactional on a RuntimeException-throwing method
        // (checked against real PostgreSQL in ApplicationContextTest).
        assertTransactional("start", String.class, String.class, Map.class, Principal.class);
        assertTransactional("execute", UUID.class, String.class, Map.class, String.class, String.class,
                Principal.class);
    }

    private static void assertTransactional(String name, Class<?>... params) {
        try {
            Transactional tx = WorkflowService.class.getMethod(name, params).getAnnotation(Transactional.class);
            assertThat(tx).isNotNull();
            assertThat(tx.rollbackFor()).isEmpty(); // default: every RuntimeException rolls back
            assertThat(tx.readOnly()).isFalse();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void historyFailureOnStartMapsTo500() {
        failHistory = true;
        assertThatThrownBy(this::startOne).isInstanceOfSatisfying(RouterException.class, e -> {
            assertThat(e.status()).isEqualTo(500);
            assertThat(e.code()).isEqualTo("HISTORY_WRITE_FAILED");
        });
        assertThat(log).doesNotContain("audit.create");
    }

    @Test
    void rejectedActionWritesNothingAndNeverMovesACursor() {
        WorkflowService.Started s = startOne();
        verify(s.instance().id());
        log.clear();

        // OFFICER may APPROVE, but REJECT needs remarks: the Router rejects before anything is persisted.
        assertThatThrownBy(() -> service.execute(s.instance().id(), "REJECT", Map.of(), null, null, officer))
                .isInstanceOf(RouterException.class);
        assertThatThrownBy(() -> service.execute(s.instance().id(), "APPROVE", Map.of(), null, null, citizen))
                .isInstanceOf(RouterException.class);
        assertThat(log).isEmpty();
        assertThat(cursors.findLastUser("GRIEVANCE", "GRIEVANCE_FLOW", "SITE_VISIT")).isEmpty();
    }

    @Test
    void lockConflictSurfacesAsOptimisticLockingFailureWhichMapsTo409() {
        WorkflowService.Started s = startOne();
        staleReads = true; // the service reads the instance as it was before a concurrent update
        assertThatThrownBy(() -> verify(s.instance().id()))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(log).doesNotContain("history.ACTION", "cursor.apply", "audit.update");
    }

    @Test
    void instanceKeepsRoutingOnItsPinnedVersionAfterANewerOneIsPublished() throws Exception {
        WorkflowService.Started s = startOne();
        assertThat(s.instance().defVersion()).isEqualTo(1);

        // Version 2 sends VERIFY straight to RESOLVE instead of REVIEW.
        ObjectNode v2 = (ObjectNode) grievanceJson();
        ((ObjectNode) v2.withArray("workflows").get(0).withArray("steps").get(0).withArray("actions").get(0))
                .put("to", "RESOLVE");
        versions.put(2, new DefinitionParser().parse(v2));

        WorkflowService.Executed e = verify(s.instance().id());
        assertThat(e.instance().step()).isEqualTo("REVIEW");
        assertThat(e.instance().defVersion()).isEqualTo(1);

        // A new start uses the newest published version.
        assertThat(startOne().instance().defVersion()).isEqualTo(2);
    }

    @Test
    void missingPinnedVersionIsAnInternalErrorNotAFallbackToLatest() {
        WorkflowService.Started s = startOne();
        versions.put(2, versions.get(1));
        versions.remove(1);
        assertThatThrownBy(() -> verify(s.instance().id()))
                .isInstanceOfSatisfying(RouterException.class,
                        e -> assertThat(e.code()).isEqualTo("PINNED_DEFINITION_MISSING"));
    }
}
