package dev.skeletonkey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.app.FormSubmissionService;
import dev.skeletonkey.app.WorkflowService;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.persistence.AssignmentCursorRepository;
import dev.skeletonkey.persistence.InstanceRepository;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Both shipped example apps in one context on real PostgreSQL 16: the backend publishes every file of
 * {@code examples/} at startup, and the asset workflow runs from Requested to In use (and on through maintenance and
 * retirement) with the assignees its strategies prescribe. The skeleton is shared; only the definitions differ.
 */
@Testcontainers
@SpringBootTest
class ExampleAppsIT {
    static final String PW = "pw-123456";
    static final Path EXAMPLES = Path.of("../../examples").toAbsolutePath().normalize();
    static final Path SEED;

    static {
        try {
            SEED = Files.createTempFile("sk-examples-seed", ".json");
            Files.writeString(SEED, "{\"users\":["
                    + user("root", "PLATFORM_ADMIN") + "," + user("requester1", "REQUESTER") + ","
                    + user("approver1", "APPROVER") + "," + user("approver2", "APPROVER") + ","
                    + user("manager1", "ASSET_MANAGER") + "," + user("manager2", "ASSET_MANAGER") + ","
                    + user("tech1", "TECHNICIAN") + "," + user("tech2", "TECHNICIAN") + "]}",
                    StandardCharsets.UTF_8);
            SEED.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String user(String name, String role) {
        return "{\"username\":\"" + name + "\",\"password\":\"" + PW + "\",\"roles\":[\"" + role
                + "\"],\"active\":true}";
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("skeletonkey.jwt.secret", () -> "0123456789abcdef0123456789abcdef-examples-it");
        r.add("skeletonkey.definitions.dir", EXAMPLES::toString);
        r.add("skeletonkey.definitions.auto-publish", () -> "true");
        r.add("skeletonkey.seed.file", SEED::toString);
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WorkflowService workflows;
    @Autowired FormSubmissionService forms;
    @Autowired UserRepository users;
    @Autowired InstanceRepository instances;
    @Autowired AssignmentCursorRepository cursors;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private JsonNode json(MockHttpServletRequestBuilder req, int expected) throws Exception {
        String body = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : mapper.readTree(body);
    }

    private String adminToken() throws Exception {
        JsonNode body = json(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", "root", "password", PW))), 200);
        return "Bearer " + body.get("token").asText();
    }

    private UUID idOf(String username) {
        return jdbc.queryForObject("select id from app_user where username = ?", UUID.class, username);
    }

    private Principal as(String username, String role) {
        return new Principal(idOf(username), Set.of(role));
    }

    /** The principal of whoever the instance is assigned to, holding the given role. */
    private Principal assignee(InstanceState inst, String role) {
        assertThat(inst.assigneeId()).as("assignee of step " + inst.step()).isNotNull();
        return new Principal(inst.assigneeId(), Set.of(role));
    }

    private UUID expectedLeastLoaded(String role) {
        List<UUID> ids = users.findActiveByRole(role).stream().map(UserAccount::id).toList();
        Map<UUID, Long> counts = instances.openTaskCounts(ids);
        return ids.stream().min(Comparator.<UUID>comparingLong(id -> counts.getOrDefault(id, 0L))
                .thenComparing(Comparator.naturalOrder())).orElseThrow();
    }

    private UUID expectedRoundRobin(String role, String step) {
        List<UUID> ids = users.findActiveByRole(role).stream().map(UserAccount::id).sorted().toList();
        return cursors.findLastUser("ASSET", "ASSET_FLOW", step)
                .flatMap(last -> ids.stream().filter(id -> id.compareTo(last) > 0).findFirst())
                .orElse(ids.get(0));
    }

    private static Map<String, Object> request(String name, Object cost) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("category", "LAPTOP");
        if (cost != null) {
            m.put("cost", cost);
        }
        m.put("location", "HQ, floor 3");
        m.put("notes", "Needed for the new analyst.");
        return m;
    }

    private InstanceState submitRequest(String name, Object cost) {
        FormSubmissionService.Result r = forms.submit("ASSET", "REQUEST_EQUIPMENT", request(name, cost),
                as("requester1", "REQUESTER"));
        return instances.findById(r.instanceId()).orElseThrow();
    }

    private InstanceState act(InstanceState inst, String action, Map<String, Object> changes, String remarks,
                              Principal who) {
        return workflows.execute(inst.id(), action, changes, remarks, null, who).instance();
    }

    // ---- both apps published in one context ----------------------------------------------------------------

    @Test
    void bothExampleAppsArePublishedAndTheAssetFileValidates() throws Exception {
        String admin = adminToken();
        for (String app : List.of("GRIEVANCE", "ASSET")) {
            JsonNode versions = json(get("/api/apps/" + app + "/definitions").header("Authorization", admin), 200);
            assertThat(versions).as(app).isNotEmpty();
            assertThat(versions.get(versions.size() - 1).get("status").asText()).as(app).isEqualTo("PUBLISHED");
        }
        JsonNode apps = json(get("/api/definitions/apps").header("Authorization", admin), 200);
        assertThat(apps.toString()).contains("\"GRIEVANCE\"").contains("\"ASSET\"");

        String asset = Files.readString(EXAMPLES.resolve("asset-register.app.json"), StandardCharsets.UTF_8);
        JsonNode violations = json(post("/api/definitions/validate").contentType(MediaType.APPLICATION_JSON)
                .content(asset).header("Authorization", admin), 200);
        assertThat(violations.toString()).isEqualTo("[]");
    }

    // ---- asset workflow ------------------------------------------------------------------------------------

    @Test
    void expensiveRequestRunsFromRequestedToInUseThroughTwoApprovalsAndLoopsThroughMaintenance() {
        InstanceState s = submitRequest("MacBook Pro", 2500.00);
        assertThat(s.step()).isEqualTo("APPROVAL");
        assertThat(s.status()).isEqualTo("Requested");
        assertThat(s.assigneeId()).as("role queue").isNull();
        assertThat(Duration.between(Instant.now(), s.dueAt()).toHours()).isBetween(46L, 48L);

        s = act(s, "APPROVE", Map.of(), null, as("approver1", "APPROVER"));
        assertThat(s.step()).as("cost over 1000 needs a second approval").isEqualTo("SECOND_APPROVAL");
        assertThat(s.assigneeId()).isNull();

        UUID manager = expectedLeastLoaded("ASSET_MANAGER");
        s = act(s, "APPROVE", Map.of(), null, as("approver2", "APPROVER"));
        assertThat(s.step()).isEqualTo("PROCUREMENT");
        assertThat(s.status()).isEqualTo("Procurement");
        assertThat(s.assigneeId()).as("least loaded asset manager").isEqualTo(manager);

        UUID technician = expectedRoundRobin("TECHNICIAN", "SETUP");
        Map<String, Object> bought = new LinkedHashMap<>();
        bought.put("purchaseDate", "2026-11-02");
        bought.put("cost", 2412.50);
        s = act(s, "RECEIVED", bought, null, assignee(s, "ASSET_MANAGER"));
        assertThat(s.step()).isEqualTo("SETUP");
        assertThat(s.status()).isEqualTo("Setting up");
        assertThat(s.assigneeId()).as("round-robin technician").isEqualTo(technician);
        assertThat(Duration.between(Instant.now(), s.dueAt()).toHours()).isBetween(70L, 72L);

        Map<String, Object> setup = new LinkedHashMap<>();
        setup.put("serialNumber", "MBP-2026-0042");
        setup.put("condition", "NEW");
        setup.put("location", "HQ, floor 3, desk 14");
        s = act(s, "HAND_OVER", setup, null, assignee(s, "TECHNICIAN"));
        assertThat(s.step()).isEqualTo("IN_USE");
        assertThat(s.status()).isEqualTo("In use");
        assertThat(s.assigneeId()).as("role queue").isNull();
        assertThat(s.ended()).isFalse();

        // maintenance loops back to In use
        UUID repairer = expectedLeastLoaded("TECHNICIAN");
        s = act(s, "SEND_TO_MAINTENANCE", Map.of(), "Battery swells", as("manager1", "ASSET_MANAGER"));
        assertThat(s.step()).isEqualTo("MAINTENANCE");
        assertThat(s.status()).isEqualTo("Under maintenance");
        assertThat(s.assigneeId()).as("least loaded technician").isEqualTo(repairer);
        Map<String, Object> repair = new LinkedHashMap<>();
        repair.put("maintenanceNotes", "Battery replaced.");
        repair.put("condition", "GOOD");
        s = act(s, "REPAIRED", repair, null, assignee(s, "TECHNICIAN"));
        assertThat(s.step()).isEqualTo("IN_USE");
        assertThat(s.status()).isEqualTo("In use");

        s = act(s, "RETIRE", Map.of(), "End of life", as("manager2", "ASSET_MANAGER"));
        assertThat(s.ended()).isTrue();
        assertThat(s.status()).isEqualTo("Retired");
    }

    @Test
    void cheapRequestSkipsTheSecondApproval() {
        InstanceState s = submitRequest("Desk lamp", 80);
        UUID manager = expectedLeastLoaded("ASSET_MANAGER");
        s = act(s, "APPROVE", Map.of(), null, as("approver1", "APPROVER"));
        assertThat(s.step()).isEqualTo("PROCUREMENT");
        assertThat(s.assigneeId()).isEqualTo(manager);
    }

    @Test
    void requestWithoutCostSkipsTheSecondApproval() {
        InstanceState s = submitRequest("Whiteboard", null);
        s = act(s, "APPROVE", Map.of(), null, as("approver1", "APPROVER"));
        assertThat(s.step()).isEqualTo("PROCUREMENT");
    }

    @Test
    void boundaryCostOf1000SkipsAndJustOverRequiresTheSecondApproval() {
        InstanceState at = act(submitRequest("Exactly 1000", 1000), "APPROVE", Map.of(), null,
                as("approver1", "APPROVER"));
        assertThat(at.step()).isEqualTo("PROCUREMENT");
        InstanceState over = act(submitRequest("Just over", 1000.01), "APPROVE", Map.of(), null,
                as("approver1", "APPROVER"));
        assertThat(over.step()).isEqualTo("SECOND_APPROVAL");
    }

    @Test
    void consecutiveSetupsAlternateBetweenTechnicians() {
        UUID first = setupAssignee(submitRequest("Monitor A", 200));
        UUID second = setupAssignee(submitRequest("Monitor B", 200));
        assertThat(second).isNotEqualTo(first);
    }

    private UUID setupAssignee(InstanceState s) {
        s = act(s, "APPROVE", Map.of(), null, as("approver1", "APPROVER"));
        Map<String, Object> bought = new LinkedHashMap<>();
        bought.put("purchaseDate", "2026-11-03");
        bought.put("cost", 200);
        s = act(s, "RECEIVED", bought, null, assignee(s, "ASSET_MANAGER"));
        assertThat(s.step()).isEqualTo("SETUP");
        return s.assigneeId();
    }

    @Test
    void registeringAnOwnedAssetSkipsApprovalAndProcurement() {
        Map<String, Object> owned = new LinkedHashMap<>();
        owned.put("name", "Standing desk");
        owned.put("category", "FURNITURE");
        owned.put("purchaseDate", "2026-01-15");
        owned.put("cost", 4200);
        FormSubmissionService.Result r = forms.submit("ASSET", "REGISTER_ASSET", owned,
                as("manager1", "ASSET_MANAGER"));
        InstanceState s = instances.findById(r.instanceId()).orElseThrow();
        assertThat(s.step()).isEqualTo("SETUP");
        assertThat(s.status()).isEqualTo("Setting up");
        assertThat(s.assigneeId()).isNotNull();
    }

    @Test
    void rolesAreEnforcedOnTheFormsAndOnTheActions() {
        Map<String, Object> payload = request("Phone", 300);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> forms.submit("ASSET", "REQUEST_EQUIPMENT", payload,
                as("approver1", "APPROVER"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        InstanceState s = submitRequest("Phone", 300);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> act(s, "APPROVE", Map.of(), null,
                as("requester1", "REQUESTER"))).hasMessageContaining("role");
    }
}
