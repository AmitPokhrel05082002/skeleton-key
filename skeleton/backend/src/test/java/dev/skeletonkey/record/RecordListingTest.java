package dev.skeletonkey.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.persistence.RecordRow;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryInstanceRepository;
import dev.skeletonkey.persistence.memory.InMemoryRecordRepository;
import dev.skeletonkey.persistence.memory.InMemoryReferenceCounter;
import dev.skeletonkey.web.BadRequest;
import dev.skeletonkey.web.NotFound;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Listing, paging, filter/sort validation, scopes and row visibility of {@link RecordService} (Req 7.9-7.14). */
class RecordListingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    private static final String APP = "GRIEVANCE";
    private static final String ENTITY = "GRIEVANCE";

    private final Principal owner = new Principal(UUID.randomUUID(), Set.of("CITIZENISH"));
    private final Principal clerk = new Principal(UUID.randomUUID(), Set.of("CLERK"));
    private final Principal inspector = new Principal(UUID.randomUUID(), Set.of("INSPECTOR"));
    private final Principal supervisor = new Principal(UUID.randomUUID(), Set.of("SUPERVISOR"));
    private final Principal stranger = new Principal(UUID.randomUUID(), Set.of("NOBODY"));

    private InMemoryRecordRepository records;
    private InMemoryInstanceRepository instances;
    private RecordService service;
    private int seq;

    private static AppDefinition definition(String viewsJson) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(Files.readString(Path.of("../../examples/grievance.app.json")));
        root.set("views", MAPPER.readTree(viewsJson));
        return new DefinitionParser().parse(root);
    }

    private void build(String viewsJson) throws Exception {
        AppDefinition def = definition(viewsJson);
        records = null;
        instances = new InMemoryInstanceRepository();
        records = new InMemoryRecordRepository(instances);
        service = new RecordService(app -> Optional.of(new PublishedDefinitions.PublishedApp(1, def)), records,
                instances, new InMemoryReferenceCounter(), new AuditService(new InMemoryAuditRepository(), CLOCK),
                CLOCK);
    }

    @BeforeEach
    void setUp() throws Exception {
        build("[]");
    }

    private RecordRow add(UUID createdBy, String subject) {
        seq++;
        Map<String, Object> data = new HashMap<>();
        data.put("subject", subject);
        RecordRow r = new RecordRow(UUID.randomUUID(), APP, 1, ENTITY, "GRV-2026-" + seq, data, createdBy,
                Instant.parse("2026-03-01T10:00:00Z").plusSeconds(seq), Instant.parse("2026-03-01T10:00:00Z"));
        records.insert(r);
        return r;
    }

    private void instance(RecordRow r, String step, UUID assignee) {
        instances.insert(new InstanceState(UUID.randomUUID(), r.id(), APP, 1, "GRIEVANCE_FLOW", step, "Received",
                assignee, null, false, Instant.now(CLOCK), 0), r.createdBy());
    }

    private RecordPage list(Principal who, String... kv) {
        Map<String, String> p = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put(kv[i], kv[i + 1]);
        return service.list(APP, ENTITY, p, who);
    }

    private static List<String> subjects(RecordPage p) {
        return p.items().stream().map(v -> (String) v.data().get("subject")).toList();
    }

    private static void assertBadParam(Runnable call, String param) {
        assertThatThrownBy(call::run).isInstanceOf(BadRequest.class).satisfies(e ->
                assertThat(((BadRequest) e).violations()).extracting(v -> v.field()).contains(param));
    }

    // ---- paging ----------------------------------------------------------------------------------------------

    @Test
    void defaultsToPageOneSizeTwentyWithTotal() {
        for (int i = 0; i < 25; i++) add(owner.userId(), "s" + i);
        RecordPage p = list(owner);
        assertThat(p.items()).hasSize(20);
        assertThat(p.page()).isEqualTo(1);
        assertThat(p.size()).isEqualTo(20);
        assertThat(p.total()).isEqualTo(25);
        assertThat(list(owner, "page", "2").items()).hasSize(5);
    }

    @Test
    void sizeBoundsZeroAndOverHundredAreRejectedOneAndHundredAccepted() {
        add(owner.userId(), "a");
        assertBadParam(() -> list(owner, "size", "0"), "size");
        assertBadParam(() -> list(owner, "size", "101"), "size");
        assertBadParam(() -> list(owner, "size", "abc"), "size");
        assertBadParam(() -> list(owner, "page", "0"), "page");
        assertThat(list(owner, "size", "1").items()).hasSize(1);
        assertThat(list(owner, "size", "100").size()).isEqualTo(100);
    }

    // ---- filters and sort ------------------------------------------------------------------------------------

    @Test
    void unknownFilterAndSortFieldAreReportedByParameter() {
        add(owner.userId(), "a");
        assertBadParam(() -> list(owner, "bogus", "x"), "bogus");
        assertBadParam(() -> list(owner, "sort", "nope,asc"), "sort");
        assertBadParam(() -> list(owner, "sort", "subject,sideways"), "sort");
        assertBadParam(() -> list(owner, "view", "MISSING"), "view");
    }

    @Test
    void filtersByEqualityAndSortsBothWays() {
        add(owner.userId(), "banana");
        add(owner.userId(), "apple");
        add(owner.userId(), "cherry");
        assertThat(subjects(list(owner, "subject", "apple"))).containsExactly("apple");
        assertThat(list(owner, "subject", "apple").total()).isEqualTo(1);
        assertThat(subjects(list(owner, "sort", "subject"))).containsExactly("apple", "banana", "cherry");
        RecordPage desc = list(owner, "sort", "subject,desc");
        assertThat(subjects(desc)).containsExactly("cherry", "banana", "apple");
        assertThat(desc.sort()).isEqualTo("subject,desc");
        assertThat(list(owner).sort()).isEqualTo("$createdAt,desc");
    }

    // ---- row visibility --------------------------------------------------------------------------------------

    @Test
    void viewRoleGrantsAccessAndViewWithoutRolesGrantsAnyAuthenticatedUser() throws Exception {
        build("[{\"code\":\"BOARD\",\"type\":\"KANBAN\",\"entity\":\"GRIEVANCE\",\"roles\":[\"SUPERVISOR\"]}]");
        RecordRow r = add(owner.userId(), "x");
        assertThat(service.read(APP, ENTITY, r.id(), supervisor).id()).isEqualTo(r.id());
        assertThat(list(supervisor).total()).isEqualTo(1);
        assertThatThrownBy(() -> service.read(APP, ENTITY, r.id(), stranger)).isInstanceOf(NotFound.class);

        build("[{\"code\":\"OPEN\",\"type\":\"TABLE\",\"entity\":\"GRIEVANCE\"}]");
        RecordRow r2 = add(owner.userId(), "y");
        assertThat(service.read(APP, ENTITY, r2.id(), stranger).id()).isEqualTo(r2.id());
        assertThat(list(stranger).total()).isEqualTo(1);
    }

    @Test
    void creatorAssigneeAndStepRoleHolderCanSeeTheRecord() {
        RecordRow r = add(owner.userId(), "x");
        instance(r, "TRIAGE", inspector.userId());
        assertThat(service.read(APP, ENTITY, r.id(), owner).id()).isEqualTo(r.id());          // b
        assertThat(service.read(APP, ENTITY, r.id(), inspector).id()).isEqualTo(r.id());      // c
        assertThat(service.read(APP, ENTITY, r.id(), clerk).id()).isEqualTo(r.id());          // d (TRIAGE = CLERK)
        assertThat(list(owner).total()).isEqualTo(1);
        assertThat(list(inspector).total()).isEqualTo(1);
        assertThat(list(clerk).total()).isEqualTo(1);
    }

    @Test
    void noClauseMeansNotFoundAndAbsentFromListsAndTotals() {
        RecordRow r = add(owner.userId(), "x");
        instance(r, "SITE_VISIT", null);   // INSPECTOR step; clerk has no clause
        assertThatThrownBy(() -> service.read(APP, ENTITY, r.id(), clerk)).isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> service.read(APP, ENTITY, r.id(), stranger)).isInstanceOf(NotFound.class);
        RecordPage p = list(clerk);
        assertThat(p.items()).isEmpty();
        assertThat(p.total()).isZero();
        // a hidden record never inflates the totals of a caller that sees others
        add(clerk.userId(), "mine");
        assertThat(list(clerk).total()).isEqualTo(1);
    }

    @Test
    void anonymousCallersSeeNothing() throws Exception {
        build("[{\"code\":\"OPEN\",\"type\":\"TABLE\",\"entity\":\"GRIEVANCE\"}]");
        RecordRow r = add(null, "x");
        Principal anon = Principal.anonymous("PUBLIC");
        assertThatThrownBy(() -> service.read(APP, ENTITY, r.id(), anon)).isInstanceOf(NotFound.class);
        assertThat(list(anon).items()).isEmpty();
        assertThat(list(anon).total()).isZero();
    }

    // ---- scopes ----------------------------------------------------------------------------------------------

    @Test
    void scopeMineListsOnlyOwnRecords() throws Exception {
        build("[{\"code\":\"M\",\"type\":\"TABLE\",\"entity\":\"GRIEVANCE\",\"scope\":\"MINE\"}]");
        add(clerk.userId(), "mine");
        add(owner.userId(), "theirs");
        assertThat(subjects(list(clerk, "view", "M"))).containsExactly("mine");
    }

    @Test
    void scopeAssignedToMeListsOnlyRecordsAssignedToCaller() throws Exception {
        build("[{\"code\":\"A\",\"type\":\"TABLE\",\"entity\":\"GRIEVANCE\",\"scope\":\"ASSIGNED_TO_ME\"}]");
        RecordRow a = add(owner.userId(), "assigned");
        instance(a, "TRIAGE", clerk.userId());
        RecordRow b = add(owner.userId(), "other");
        instance(b, "TRIAGE", UUID.randomUUID());
        RecordRow c = add(owner.userId(), "queued");
        instance(c, "TRIAGE", null);
        assertThat(subjects(list(clerk, "view", "A"))).containsExactly("assigned");
    }

    @Test
    void scopeMyRoleQueueListsUnassignedOpenRecordsAtStepsOfCallersRole() throws Exception {
        build("[{\"code\":\"Q\",\"type\":\"TABLE\",\"entity\":\"GRIEVANCE\",\"scope\":\"MY_ROLE_QUEUE\"}]");
        RecordRow queued = add(owner.userId(), "queued");
        instance(queued, "TRIAGE", null);
        RecordRow taken = add(owner.userId(), "taken");
        instance(taken, "TRIAGE", UUID.randomUUID());
        RecordRow elsewhere = add(owner.userId(), "elsewhere");
        instance(elsewhere, "SITE_VISIT", null);
        assertThat(subjects(list(clerk, "view", "Q"))).containsExactly("queued");
        assertThat(subjects(list(inspector, "view", "Q"))).containsExactly("elsewhere");
    }

    @Test
    void viewRolesRestrictWhoMayUseTheView() throws Exception {
        build("[{\"code\":\"ALL\",\"type\":\"TABLE\",\"entity\":\"GRIEVANCE\",\"roles\":[\"SUPERVISOR\"]}]");
        add(owner.userId(), "x");
        assertBadParam(() -> list(clerk, "view", "ALL"), "view");
        assertThat(list(supervisor, "view", "ALL").total()).isEqualTo(1);
    }
}
