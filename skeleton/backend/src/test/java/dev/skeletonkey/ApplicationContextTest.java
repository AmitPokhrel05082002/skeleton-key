package dev.skeletonkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.app.WorkflowService;
import dev.skeletonkey.domain.route.RouterException;
import dev.skeletonkey.persistence.HistoryRepository;
import dev.skeletonkey.persistence.InstanceRepository;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.UserRepository;
import dev.skeletonkey.plugin.HandlerResult;
import dev.skeletonkey.plugin.PluginHandler;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.definition.DefinitionService;
import dev.skeletonkey.definition.DefinitionVersionRef;
import dev.skeletonkey.persistence.DefinitionVersionRow;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.record.PublishedDefinitions;
import dev.skeletonkey.record.RecordPage;
import dev.skeletonkey.record.RecordService;
import dev.skeletonkey.record.RecordView;
import dev.skeletonkey.web.BadRequest;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the full application context against a real PostgreSQL 16 (Testcontainers, random port) so real bean wiring,
 * property binding, Flyway, the SeedLoader, security and DefinitionService are exercised together.
 */
@Testcontainers
@SpringBootTest
@Import(ApplicationContextTest.Handlers.class)
class ApplicationContextTest {
    static final String JWT_SECRET = "0123456789abcdef0123456789abcdef-context-test";
    static final String ADMIN_PW = "admin-pw-123";
    static final String USER_PW = "user-pw-123";
    static final Path GRIEVANCE = Path.of("../../examples/grievance.app.json");
    static final Path SEED;
    static final Path DEFINITIONS_DIR;

    static {
        try {
            DEFINITIONS_DIR = Files.createTempDirectory("sk-defs");
            Path startupCopy = DEFINITIONS_DIR.resolve("grievance.app.json");
            Files.copy(GRIEVANCE, startupCopy);
            startupCopy.toFile().deleteOnExit();
            SEED = Files.createTempFile("sk-seed", ".json");
            Files.writeString(SEED, "{\"users\":["
                    + "{\"username\":\"root\",\"password\":\"" + ADMIN_PW + "\",\"roles\":[\"PLATFORM_ADMIN\"]},"
                    + "{\"username\":\"citizen\",\"password\":\"" + USER_PW + "\",\"roles\":[\"OFFICER\"]}]}",
                    StandardCharsets.UTF_8);
            SEED.toFile().deleteOnExit();
            DEFINITIONS_DIR.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("skeletonkey.jwt.secret", () -> JWT_SECRET);
        r.add("skeletonkey.definitions.dir", DEFINITIONS_DIR::toString);
        r.add("skeletonkey.seed.file", SEED::toString);
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DefinitionService definitions;
    @Autowired PublishedDefinitions published;
    @Autowired ObjectMapper mapper;
    @Autowired RecordService recordService;
    @Autowired WorkflowService workflows;
    @Autowired UserRepository users;
    @Autowired InstanceRepository instanceRepository;
    @MockitoSpyBean HistoryRepository historySpy;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    @Test
    void flywayAppliedAndCoreTablesExist() {
        Integer failed = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Integer.class);
        Integer ok = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Integer.class);
        assertThat(failed).isZero();
        assertThat(ok).isGreaterThanOrEqualTo(1);
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("definition_version", "app_user", "user_role", "record", "reference_counter",
                "workflow_instance", "assignment_cursor", "history_entry", "audit_log");
    }

    @Test
    void grievanceDefinitionSubmitsAndPublishesAsSystemActor() {
        // StartupLoader read grievance.app.json from the temp definitions dir before the context finished starting.
        // Other tests may add further GRIEVANCE versions, so look at the version created from the file (the first).
        var row = jdbc.queryForMap("select id, status, content_hash from definition_version "
                + "where app_code = 'GRIEVANCE' and version = 1");
        assertThat(row.get("status")).isEqualTo(DefinitionVersionRow.PUBLISHED);
        assertThat((String) row.get("content_hash")).isNotBlank();
        String id = row.get("id").toString();

        assertThat(published.latestPublished("GRIEVANCE")).isPresent();
        assertThat(published.publishedVersion("GRIEVANCE", 1)).isPresent();

        // Exactly one publish audit row, written by the reserved system actor.
        var audits = jdbc.queryForList(
                "select actor_id from audit_log where action = ? and target_type = ? and target_id = ?",
                AuditService.PUBLISH, DefinitionService.AUDIT_TARGET, id);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("actor_id")).isEqualTo(DefinitionService.SYSTEM_ACTOR.userId());
    }

    @Test
    void seededUsersCanLoginAndOnlyAdminMayUseDefinitions() throws Exception {
        Integer users = jdbc.queryForObject(
                "select count(*) from app_user where username in ('root','citizen')", Integer.class);
        assertThat(users).isEqualTo(2);

        String adminToken = login("root", ADMIN_PW);
        String userToken = login("citizen", USER_PW);
        String body = Files.readString(GRIEVANCE, StandardCharsets.UTF_8);

        mvc.perform(post("/api/definitions").header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/definitions").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /**
     * Exercises the JDBC adapters on real PostgreSQL through the real context: reference numbering, JSONB
     * round trip, UUID/timestamp mapping, paging, filter, sort and the visibility SQL.
     */
    @Test
    void jdbcAdaptersCreateReadAndListRecordsOnRealPostgres() throws IOException {
        if (published.latestPublished("GRIEVANCE").isEmpty()) {
            DefinitionVersionRef d = definitions.submit(Files.readString(GRIEVANCE, StandardCharsets.UTF_8));
            definitions.publish(d.id(), DefinitionService.SYSTEM_ACTOR);
        }
        UUID citizenId = jdbc.queryForObject("select id from app_user where username = 'citizen'", UUID.class);
        Principal citizen = new Principal(citizenId, Set.of("OFFICER"));

        RecordView a = recordService.create("GRIEVANCE", "GRIEVANCE",
                grievance("Pothole on Main", "WATER", true), citizen);
        RecordView b = recordService.create("GRIEVANCE", "GRIEVANCE",
                grievance("Broken tap", "WATER", false), citizen);
        RecordView c = recordService.create("GRIEVANCE", "GRIEVANCE",
                grievance("Overflowing bin", "WASTE", false), citizen);

        // reference numbering: GRV-<year>-<6 digits>, consecutive per prefix and year
        Pattern ref = Pattern.compile("^GRV-(\\d{4})-(\\d{6})$");
        Matcher ma = ref.matcher(a.referenceNo());
        Matcher mb = ref.matcher(b.referenceNo());
        Matcher mc = ref.matcher(c.referenceNo());
        assertThat(ma.matches() && mb.matches() && mc.matches()).isTrue();
        int na = Integer.parseInt(ma.group(2));
        assertThat(Integer.parseInt(mb.group(2))).isEqualTo(na + 1);
        assertThat(Integer.parseInt(mc.group(2))).isEqualTo(na + 2);

        // read back: JSONB data, UUID and timestamps
        RecordView back = recordService.read("GRIEVANCE", "GRIEVANCE", a.id(), citizen);
        assertThat(back.id()).isEqualTo(a.id());
        assertThat(back.referenceNo()).isEqualTo(a.referenceNo());
        assertThat(back.createdBy()).isEqualTo(citizenId);
        assertThat(back.createdAt()).isNotNull();
        assertThat(back.data()).containsEntry("subject", "Pothole on Main")
                .containsEntry("category", "WATER").containsEntry("urgent", true);

        // list with paging and total
        Map<String, String> p1 = Map.of("size", "2", "page", "1", "sort", "$reference,asc");
        RecordPage first = recordService.list("GRIEVANCE", "GRIEVANCE", p1, citizen);
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.items()).extracting(RecordView::referenceNo)
                .containsExactly(a.referenceNo(), b.referenceNo());
        RecordPage second = recordService.list("GRIEVANCE", "GRIEVANCE",
                Map.of("size", "2", "page", "2", "sort", "$reference,asc"), citizen);
        assertThat(second.items()).extracting(RecordView::referenceNo).containsExactly(c.referenceNo());

        // filter on a JSONB field
        RecordPage water = recordService.list("GRIEVANCE", "GRIEVANCE", Map.of("category", "WATER"), citizen);
        assertThat(water.total()).isEqualTo(2);

        // sort on a JSONB field
        RecordPage bySubject = recordService.list("GRIEVANCE", "GRIEVANCE", Map.of("sort", "subject,asc"), citizen);
        assertThat(bySubject.items()).extracting(r -> r.data().get("subject"))
                .containsExactly("Broken tap", "Overflowing bin", "Pothole on Main");

        // visibility SQL: a user who did not create the rows and holds no granting role sees none of them
        Principal stranger = new Principal(UUID.randomUUID(), Set.of());
        assertThat(recordService.list("GRIEVANCE", "GRIEVANCE", Map.of(), stranger).total()).isZero();

        // unknown filter field is rejected, not turned into SQL
        assertThatThrownBy(() -> recordService.list("GRIEVANCE", "GRIEVANCE", Map.of("nope", "x"), citizen))
                .isInstanceOf(BadRequest.class);
    }

    /** Plugin handler used by the AUTO step of the end-to-end definition. */
    @TestConfiguration
    static class Handlers {
        @Bean
        PluginHandler stampHandler() {
            return ctx -> HandlerResult.of(Map.of("inspectionNotes", "stamped"));
        }
    }

    /**
     * Start and execute against real PostgreSQL through WorkflowService: the grievance app with an AUTO step, so
     * migration V2 (history kind AUTO) and the JDBC history adapter are exercised, then a forced history write
     * failure to check the real transaction rolls back every earlier write.
     */
    @Test
    void workflowStartAndExecuteOnRealPostgresIncludingAutoHistoryAndRollback() throws Exception {
        ObjectNode doc = (ObjectNode) mapper.readTree(Files.readString(GRIEVANCE, StandardCharsets.UTF_8));
        ((ObjectNode) doc.get("app")).put("code", "GRVAUTO");
        ObjectNode flow = (ObjectNode) doc.get("workflows").get(0);
        ArrayNode steps = (ArrayNode) flow.get("steps");
        ((ObjectNode) steps.get(0).get("actions").get(0)).put("to", "STAMP");
        ObjectNode stamp = mapper.createObjectNode();
        stamp.put("code", "STAMP").put("label", "Stamp").put("type", "AUTO").put("handler", "stampHandler");
        steps.insert(1, stamp); // next in array order is REVIEW
        DefinitionVersionRef draft = definitions.submit(mapper.writeValueAsString(doc));
        definitions.publish(draft.id(), DefinitionService.SYSTEM_ACTOR);

        UUID clerkId = UUID.randomUUID();
        users.insert(new UserAccount(clerkId, "clerk-wf", "x", "Clerk", true, Set.of("CLERK")));
        UUID inspectorId = UUID.randomUUID();
        users.insert(new UserAccount(inspectorId, "inspector-wf", "x", "Inspector", true, Set.of("INSPECTOR")));
        UUID officerId = jdbc.queryForObject("select id from app_user where username = 'citizen'", UUID.class);
        Principal clerk = new Principal(clerkId, Set.of("CLERK"));
        Principal officer = new Principal(officerId, Set.of("OFFICER"));

        WorkflowService.Started started = workflows.start("GRVAUTO", "GRIEVANCE_FLOW",
                grievance("Auto path", "ROAD", false), Principal.anonymous("PUBLIC"));
        UUID instanceId = started.instance().id();
        assertThat(started.instance().assigneeId()).isEqualTo(clerkId);
        assertThat(started.instance().defVersion()).isEqualTo(draft.version());

        WorkflowService.Executed verified = workflows.execute(instanceId, "VERIFY", Map.of(), "ok", null, clerk);
        assertThat(verified.instance().step()).isEqualTo("REVIEW");
        assertThat(verified.instance().lockVersion()).isEqualTo(1L);
        // The NOTIFY onExit event adds EVENT_DEFERRED; the START/ACTION/AUTO chain is what V2 must accept.
        assertThat(jdbc.queryForList("select kind from history_entry where instance_id = ? "
                + "and kind in ('START','ACTION','SKIPPED','AUTO') order by event_time, seq",
                String.class, instanceId)).containsExactly("START", "ACTION", "AUTO");
        assertThat(recordService.read("GRVAUTO", "GRIEVANCE", started.record().id(), officer).data())
                .containsEntry("inspectionNotes", "stamped");

        // Forced history failure: the record, instance, history, cursor and audit writes all roll back.
        int historyBefore = count("select count(*) from history_entry where instance_id = ?", instanceId);
        int auditBefore = count("select count(*) from audit_log where target_id = ?", started.record().id().toString());
        doThrow(new DataAccessResourceFailureException("simulated")).when(historySpy).append(any(), any());
        assertThatThrownBy(() -> workflows.execute(instanceId, "APPROVE", Map.of("resolutionNotes", "fix"),
                null, null, officer))
                .isInstanceOfSatisfying(RouterException.class,
                        e -> assertThat(e.code()).isEqualTo("HISTORY_WRITE_FAILED"));
        reset(historySpy);
        assertThat(count("select count(*) from history_entry where instance_id = ?", instanceId))
                .isEqualTo(historyBefore);
        assertThat(count("select count(*) from audit_log where target_id = ?", started.record().id().toString()))
                .isEqualTo(auditBefore);
        assertThat(jdbc.queryForObject("select current_step || '/' || lock_version from workflow_instance "
                + "where id = ?", String.class, instanceId)).isEqualTo("REVIEW/1");
        assertThat(recordService.read("GRVAUTO", "GRIEVANCE", started.record().id(), officer).data())
                .doesNotContainKey("resolutionNotes");
        assertThat(count("select count(*) from assignment_cursor where app_code = 'GRVAUTO'")).isZero();

        // The same request succeeds afterwards and then applies the cursor.
        WorkflowService.Executed approved = workflows.execute(instanceId, "APPROVE",
                Map.of("resolutionNotes", "fix"), null, null, officer);
        assertThat(approved.instance().step()).isEqualTo("SITE_VISIT");
        assertThat(approved.instance().assigneeId()).isEqualTo(inspectorId);
        assertThat(count("select count(*) from assignment_cursor where app_code = 'GRVAUTO'")).isEqualTo(1);

        // A stale writer loses: updating with an old lock version is a 409 CONCURRENT_MODIFICATION.
        assertThatThrownBy(() -> instanceRepository.update(verified.instance()))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static Map<String, Object> grievance(String subject, String category, boolean urgent) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("subject", subject);
        m.put("details", "Details for " + subject);
        m.put("category", category);
        m.put("contactEmail", "someone@example.org");
        m.put("urgent", urgent);
        return m;
    }
    private String login(String username, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = mapper.readTree(r.getResponse().getContentAsString());
        assertThat(node.get("token").asText()).isNotBlank();
        return node.get("token").asText();
    }
}
