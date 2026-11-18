package dev.skeletonkey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.app.WorkflowService;
import dev.skeletonkey.domain.model.Assignee;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.persistence.TrackingRepository;
import dev.skeletonkey.persistence.UserRepository;
import dev.skeletonkey.persistence.UserRow;
import dev.skeletonkey.persistence.UserSearch;
import dev.skeletonkey.router.AssigneeResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
 * user-admin-and-tracking on real PostgreSQL 16: the V3 case-insensitive username index, the admin search SQL,
 * role replacement and password reset, deactivation against LEAST_LOADED / ROUND_ROBIN with existing tasks staying
 * visible, the active check on tokens, and the tracking SQL (match, wildcards, snapshot, timeline).
 */
@Testcontainers
@SpringBootTest
class UserAdminAndTrackingIT {
    static final String JWT_SECRET = "0123456789abcdef0123456789abcdef-user-admin-it";
    static final String PW = "pw-123456";
    static final Path GRIEVANCE = Path.of("../../examples/grievance.app.json");
    static final Path SEED;
    static final Path DEFINITIONS_DIR;

    static {
        try {
            DEFINITIONS_DIR = Files.createTempDirectory("sk-uat-defs");
            Path copy = DEFINITIONS_DIR.resolve("grievance.app.json");
            Files.copy(GRIEVANCE, copy);
            copy.toFile().deleteOnExit();
            DEFINITIONS_DIR.toFile().deleteOnExit();
            SEED = Files.createTempFile("sk-uat-seed", ".json");
            Files.writeString(SEED, "{\"users\":[" + user("root", "PLATFORM_ADMIN") + "," + user("boss", "SUPERVISOR")
                    + "," + user("clerkA", "CLERK") + "," + user("clerkB", "CLERK") + ","
                    + user("inspA", "INSPECTOR") + "," + user("inspB", "INSPECTOR") + "]}", StandardCharsets.UTF_8);
            SEED.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String user(String name, String role) {
        return "{\"username\":\"" + name + "\",\"password\":\"" + PW + "\",\"roles\":[\"" + role + "\"]}";
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
        r.add("skeletonkey.track.rate-limit.per-minute", () -> "10000");
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired WorkflowService workflows;
    @Autowired AssigneeResolver resolver;
    @Autowired TrackingRepository tracking;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", PW))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(body).get("token").asText();
    }

    private int loginStatus(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode call(MockHttpServletRequestBuilder b, String auth, Object body, int expected) throws Exception {
        if (auth != null) {
            b.header("Authorization", auth);
        }
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        String s = mvc.perform(b).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return s.isBlank() ? null : mapper.readTree(s);
    }

    private UUID idOf(String username) {
        return jdbc.queryForObject("select id from app_user where lower(username) = lower(?)", UUID.class, username);
    }

    private static Map<String, Object> payload(String subject, String email) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", subject);
        m.put("details", "Details for " + subject);
        m.put("category", "ROAD");
        m.put("contactEmail", email);
        m.put("urgent", false);
        return m;
    }

    private WorkflowService.Started start(String subject, String email) {
        return workflows.start("GRIEVANCE", "GRIEVANCE_FLOW", payload(subject, email), Principal.anonymous("PUBLIC"));
    }

    // ---- usernames (V3) -----------------------------------------------------------------------------------

    @Test
    void usernamesAreUniqueIgnoringCaseInTheDatabaseAndInTheApi() throws Exception {
        String admin = login("root");
        call(post("/api/admin/users"), admin, Map.of("username", "Asha", "displayName", "Asha Rai", "password", PW,
                "roles", List.of("CLERK")), 201);
        JsonNode dup = call(post("/api/admin/users"), admin, Map.of("username", "asha", "displayName", "Other",
                "password", PW), 409);
        assertThat(dup.get("code").asText()).isEqualTo("USERNAME_TAKEN");
        assertThatThrownBy(() -> jdbc.update("insert into app_user (id, username, password_hash) values (?, ?, ?)",
                UUID.randomUUID(), "ASHA", "x")).isInstanceOf(DataIntegrityViolationException.class);
        for (String casing : new String[] {"Asha", "asha", "ASHA"}) {
            assertThat(loginStatus(casing, PW)).as(casing).isEqualTo(200);
        }
        assertThat(users.findByUsername("aSHA").orElseThrow().username()).isEqualTo("Asha");
    }

    // ---- search SQL, update, password ---------------------------------------------------------------------

    @Test
    void searchFiltersSortsAndPagesOnPostgres() throws Exception {
        String admin = login("root");
        call(post("/api/admin/users"), admin, Map.of("username", "srch_one", "displayName", "Zed 100%", "password",
                PW, "roles", List.of("OFFICER")), 201);
        call(post("/api/admin/users"), admin, Map.of("username", "srchxtwo", "displayName", "Amy", "password", PW,
                "roles", List.of("OFFICER", "CLERK")), 201);
        JsonNode three = call(post("/api/admin/users"), admin, Map.of("username", "srch-three",
                "displayName", "back\\slash", "password", PW), 201);
        call(patch("/api/admin/users/" + three.get("id").asText()), admin, Map.of("active", false), 200);

        assertThat(users.search(new UserSearch("srch", null, null, 0, 50, UserSearch.Field.USERNAME, true))
                .total()).isEqualTo(3);
        // '_' and '%' and '\' are literal.
        List<UserRow> underscore = users.search(new UserSearch("srch_", null, null, 0, 50,
                UserSearch.Field.USERNAME, true)).items();
        assertThat(underscore).extracting(UserRow::username).containsExactly("srch_one");
        assertThat(users.search(new UserSearch("100%", null, null, 0, 50, UserSearch.Field.USERNAME, true))
                .items()).extracting(UserRow::username).containsExactly("srch_one");
        assertThat(users.search(new UserSearch("k\\s", null, null, 0, 50, UserSearch.Field.USERNAME, true))
                .items()).extracting(UserRow::username).containsExactly("srch-three");
        // Role and active filters, combined, with roles loaded for the page.
        List<UserRow> officersActive = users.search(new UserSearch("SRCH", "OFFICER", true, 0, 50,
                UserSearch.Field.DISPLAY_NAME, false)).items();
        assertThat(officersActive).extracting(UserRow::username).containsExactly("srch_one", "srchxtwo");
        assertThat(officersActive.get(1).roles()).containsExactlyInAnyOrder("OFFICER", "CLERK");
        assertThat(users.search(new UserSearch("srch", null, false, 0, 50, UserSearch.Field.USERNAME, true))
                .items()).extracting(UserRow::username).containsExactly("srch-three");
        // Stable paging: the pages together hold every row exactly once.
        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            users.search(new UserSearch("srch", null, null, page, 1, UserSearch.Field.CREATED_AT, true)).items()
                    .forEach(r -> paged.add(r.username()));
        }
        assertThat(paged).containsExactlyInAnyOrder("srch_one", "srchxtwo", "srch-three");

        JsonNode page = call(get("/api/admin/users?q=srch&sort=displayName,asc&size=2"), admin, null, 200);
        assertThat(page.get("total").asInt()).isEqualTo(3);
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.toString()).doesNotContain("$2").doesNotContain("password");
    }

    @Test
    void rolesAreReplacedAndPasswordsResetOnPostgres() throws Exception {
        String admin = login("root");
        JsonNode u = call(post("/api/admin/users"), admin, Map.of("username", "roleswap", "displayName", "R",
                "password", PW, "roles", List.of("CLERK", "OFFICER")), 201);
        String url = "/api/admin/users/" + u.get("id").asText();
        call(patch(url), admin, Map.of("roles", List.of("SUPERVISOR"), "displayName", "Renamed"), 200);
        assertThat(jdbc.queryForList("select role_code from user_role where user_id = ?", String.class,
                UUID.fromString(u.get("id").asText()))).containsExactly("SUPERVISOR");
        String hashBefore = jdbc.queryForObject("select password_hash from app_user where username = 'roleswap'",
                String.class);
        call(post(url + "/password"), admin, Map.of("password", "brand-new-pw"), 204);
        String hashAfter = jdbc.queryForObject("select password_hash from app_user where username = 'roleswap'",
                String.class);
        assertThat(hashAfter).isNotEqualTo(hashBefore).doesNotContain("brand-new-pw");
        assertThat(loginStatus("roleswap", "brand-new-pw")).isEqualTo(200);
        assertThat(loginStatus("roleswap", PW)).isEqualTo(401);
    }

    // ---- deactivation -------------------------------------------------------------------------------------

    @Test
    void deactivatedUsersAreSkippedByAssignmentAndTheirTasksStayVisible() throws Exception {
        String admin = login("root");
        String clerkAToken = login("clerkA");
        UUID clerkA = idOf("clerkA");

        // Make sure clerkA holds an open task before being deactivated.
        WorkflowService.Started held = null;
        for (int i = 0; i < 12 && held == null; i++) {
            WorkflowService.Started s = start("before-" + i, "a@b.org");
            if (clerkA.equals(s.instance().assigneeId())) {
                held = s;
            }
        }
        assertThat(held).as("LEAST_LOADED gave clerkA a task").isNotNull();

        call(patch("/api/admin/users/" + clerkA), admin, Map.of("active", false), 200);
        // The existing token is rejected on the next request.
        call(get("/api/auth/me"), clerkAToken, null, 401);
        assertThat(loginStatus("clerkA", PW)).isEqualTo(401);

        for (int i = 0; i < 5; i++) {
            // Other tests in this class add active CLERK users, so assert the property itself:
            // the deactivated clerk is never chosen, and the task still goes to someone.
            assertThat(start("after-" + i, "a@b.org").instance().assigneeId()).isNotNull().isNotEqualTo(clerkA);
        }
        UUID inspA = idOf("inspA");
        call(patch("/api/admin/users/" + inspA), admin, Map.of("active", false), 200);
        for (int i = 0; i < 4; i++) {
            AssigneeResolver.Resolution r = resolver.resolve(new Assignee(Assignee.Strategy.ROUND_ROBIN,
                    "INSPECTOR", null), "GRIEVANCE", "GRIEVANCE_FLOW", "SITE_VISIT", null, Map.of());
            assertThat(r.assignee()).contains(idOf("inspB"));
        }

        // The deactivated clerk's task is unchanged and visible to the supervisor with the clerk's name.
        assertThat(jdbc.queryForObject("select assignee from workflow_instance where id = ?", UUID.class,
                held.instance().id())).isEqualTo(clerkA);
        JsonNode rec = call(get("/api/apps/GRIEVANCE/entities/GRIEVANCE/records/" + held.record().id()),
                login("boss"), null, 200);
        assertThat(rec.get("assignee").asText()).isEqualTo(clerkA.toString());
        assertThat(rec.get("assigneeName").asText()).isEqualTo("clerkA");

        call(patch("/api/admin/users/" + clerkA), admin, Map.of("active", true), 200);
        call(patch("/api/admin/users/" + inspA), admin, Map.of("active", true), 200);
        assertThat(loginStatus("clerkA", PW)).isEqualTo(200);
    }

    // ---- tracking SQL -------------------------------------------------------------------------------------

    @Test
    void trackingMatchesOnPostgresAndShowsOnlyPublicData() throws Exception {
        WorkflowService.Started s = start("Broken street light", "Citizen@Example.org");
        String ref = s.record().referenceNo();
        UUID assignee = s.instance().assigneeId();
        workflows.execute(s.instance().id(), "VERIFY", Map.of(), "SECRET-REMARK", null,
                new Principal(assignee, Set.of("CLERK")));

        Map<String, String> body = Map.of("reference", ref.toLowerCase(), "email", "  citizen@EXAMPLE.org ");
        JsonNode ok = call(post("/api/public/GRIEVANCE/track"), null, body, 200);
        assertThat(ok.get("reference").asText()).isEqualTo(ref);
        assertThat(ok.get("title").asText()).isEqualTo("Broken street light");
        assertThat(ok.get("status").get("label").asText()).isEqualTo("Under review");
        assertThat(ok.get("status").get("color").asText()).isEqualTo("#f59e0b");
        List<String> timeline = new ArrayList<>();
        ok.get("timeline").forEach(e -> timeline.add(e.get("status").asText()));
        assertThat(timeline).containsExactly("Received", "Under review");
        assertThat(ok.toString()).doesNotContain("SECRET").doesNotContainIgnoringCase("citizen@example.org")
                .doesNotContain(assignee.toString()).doesNotContain("TRIAGE");

        for (String probe : new String[] {"%", "_itizen@example.org", "%@example.org", "citizen@example.or",
                "citizen@example.org.", ".*"}) {
            assertThat(tracking.contactMatches(s.record().id(), "contactEmail", probe)).as(probe).isFalse();
            call(post("/api/public/GRIEVANCE/track"), null, Map.of("reference", ref, "email", probe), 404);
        }
        assertThat(tracking.contactMatches(s.record().id(), "contactEmail", "citizen@example.org")).isTrue();
        assertThat(tracking.contactMatches(s.record().id(), "noSuchField", "citizen@example.org")).isFalse();
        assertThat(tracking.findCandidate("GRIEVANCE", ref.toLowerCase())).isEmpty();
        assertThat(tracking.findCandidate("OTHER_APP", ref)).isEmpty();
        call(post("/api/public/GRIEVANCE/track"), null, Map.of("reference", ref + "9", "email",
                "citizen@example.org"), 404);
    }
}
