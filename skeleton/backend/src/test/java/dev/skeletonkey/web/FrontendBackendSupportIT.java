package dev.skeletonkey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.app.WorkflowService;
import dev.skeletonkey.domain.model.HistoryEntry;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.persistence.HistoryRepository;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.UserRepository;
import dev.skeletonkey.record.RecordService;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
 * The frontend-support endpoints on real PostgreSQL 16 (Testcontainers): Me, App list, Version list, Validate, the
 * record workflow summary, display names, and the number of SQL statements a request issues (Req 2.14, 3.6, 8.5-8.9).
 */
@Testcontainers
@SpringBootTest
@Import(FrontendBackendSupportIT.CountingDataSource.class)
class FrontendBackendSupportIT {
    static final String JWT_SECRET = "0123456789abcdef0123456789abcdef-frontend-it";
    static final String PW = "pw-123456";
    static final Path GRIEVANCE = Path.of("../../examples/grievance.app.json");
    static final Path SEED;
    static final Path DEFINITIONS_DIR;
    static final List<String> SQL = java.util.Collections.synchronizedList(new ArrayList<>());

    static {
        try {
            DEFINITIONS_DIR = Files.createTempDirectory("sk-it-defs");
            Path copy = DEFINITIONS_DIR.resolve("grievance.app.json");
            Files.copy(GRIEVANCE, copy);
            copy.toFile().deleteOnExit();
            DEFINITIONS_DIR.toFile().deleteOnExit();
            SEED = Files.createTempFile("sk-it-seed", ".json");
            Files.writeString(SEED, "{\"users\":["
                    + user("root", "PLATFORM_ADMIN", true) + "," + user("boss", "SUPERVISOR", true) + ","
                    + user("clerk1", "CLERK", true) + "," + user("citizen", "OFFICER", true) + ","
                    + user("sleeper", "CLERK", false) + "]}", StandardCharsets.UTF_8);
            SEED.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String user(String name, String role, boolean active) {
        return "{\"username\":\"" + name + "\",\"password\":\"" + PW + "\",\"roles\":[\"" + role + "\"],\"active\":"
                + active + "}";
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

    /** Wraps the DataSource so every prepared or plain statement text is recorded in {@link #SQL}. */
    @TestConfiguration
    static class CountingDataSource {
        @Bean
        static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
                    if (!(bean instanceof DataSource target) || Proxy.isProxyClass(bean.getClass())) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(),
                            allInterfaces(bean.getClass()), (p, m, a) -> {
                                Object result = invoke(m, target, a);
                                return m.getName().equals("getConnection") ? connection((java.sql.Connection) result)
                                        : result;
                            });
                }
            };
        }

        private static Class<?>[] allInterfaces(Class<?> type) {
            return org.springframework.util.ClassUtils.getAllInterfacesForClass(type);
        }

        private static java.sql.Connection connection(java.sql.Connection target) {
            return (java.sql.Connection) Proxy.newProxyInstance(target.getClass().getClassLoader(),
                    new Class<?>[] {java.sql.Connection.class}, (p, m, a) -> {
                        if ((m.getName().equals("prepareStatement") || m.getName().equals("prepareCall"))
                                && a != null && a.length > 0 && a[0] instanceof String sql) {
                            SQL.add(sql);
                        }
                        return invoke(m, target, a);
                    });
        }

        private static Object invoke(java.lang.reflect.Method m, Object target, Object[] a) throws Throwable {
            try {
                return m.invoke(target, a);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired WorkflowService workflows;
    @Autowired RecordService records;
    @Autowired HistoryRepository history;
    MockMvc mvc;
    String grievance;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        grievance = Files.readString(GRIEVANCE, StandardCharsets.UTF_8);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", PW))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(body).get("token").asText();
    }

    private JsonNode json(MockHttpServletRequestBuilder req, int expected) throws Exception {
        String body = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : mapper.readTree(body);
    }

    private UUID idOf(String username) {
        return jdbc.queryForObject("select id from app_user where username = ?", UUID.class, username);
    }

    private static Map<String, Object> payload(String subject) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", subject);
        m.put("details", "Details for " + subject);
        m.put("category", "ROAD");
        m.put("contactEmail", "someone@example.org");
        m.put("urgent", false);
        return m;
    }

    private WorkflowService.Started start(String subject) {
        return workflows.start("GRIEVANCE", "GRIEVANCE_FLOW", payload(subject), Principal.anonymous("PUBLIC"));
    }

    private Principal clerk() {
        return new Principal(idOf("clerk1"), Set.of("CLERK"));
    }

    private int sqlDuring(Runnable r) {
        int before = SQL.size();
        r.run();
        return SQL.size() - before;
    }

    private int statementsFor(String url, String auth) {
        return sqlDuring(() -> {
            try {
                json(get(url).header("Authorization", auth), 200);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ---- GET /api/auth/me ---------------------------------------------------------------------------------

    @Test
    void meReturnsTheStoredUserAndRejectsBadTokens() throws Exception {
        JsonNode me = json(get("/api/auth/me").header("Authorization", login("boss")), 200);
        assertThat(me.get("userId").asText()).isEqualTo(idOf("boss").toString());
        assertThat(me.get("displayName").asText()).isEqualTo("boss");
        assertThat(me.get("roles")).hasSize(1);
        assertThat(me.get("roles").get(0).asText()).isEqualTo("SUPERVISOR");
        assertThat(me.size()).isEqualTo(3);

        json(get("/api/auth/me"), 401);
        String t = login("boss");
        json(get("/api/auth/me").header("Authorization", t.substring(0, t.length() - 3) + "abc"), 401);
        UserAccount sleeper = users.findByUsername("sleeper").orElseThrow();
        json(get("/api/auth/me").header("Authorization", "Bearer " + jwt.issue(sleeper)), 401);
        UserAccount ghost = new UserAccount(UUID.randomUUID(), "ghost", "x", "Ghost", true, Set.of("CLERK"));
        json(get("/api/auth/me").header("Authorization", "Bearer " + jwt.issue(ghost)), 401);
    }

    // ---- App list and version list ------------------------------------------------------------------------

    @Test
    void appAndVersionListsFollowTheAdminRules() throws Exception {
        String admin = login("root");
        JsonNode apps = json(get("/api/definitions/apps").header("Authorization", admin), 200);
        assertThat(apps.isArray()).isTrue();
        assertThat(apps.toString()).contains("\"GRIEVANCE\"");

        JsonNode versions = json(get("/api/apps/GRIEVANCE/definitions").header("Authorization", admin), 200);
        assertThat(versions.get(0).get("version").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode first = versions.get(versions.size() - 1);
        assertThat(first.get("version").asInt()).isEqualTo(1);
        assertThat(first.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(first.get("publishedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(first.has("document")).isFalse();

        json(get("/api/apps/NOPE/definitions").header("Authorization", admin), 404);
        json(get("/api/apps/lower-case/definitions").header("Authorization", admin), 404);
        String boss = login("boss");
        json(get("/api/definitions/apps").header("Authorization", boss), 403);
        json(get("/api/apps/GRIEVANCE/definitions").header("Authorization", boss), 403);
        json(get("/api/apps/NOPE/definitions").header("Authorization", boss), 403);
        json(get("/api/definitions/apps"), 401);
        json(get("/api/apps/GRIEVANCE/definitions"), 401);
    }

    // ---- POST /api/definitions/validate -------------------------------------------------------------------

    @Test
    void validateAnswersEveryStatusAndStoresNothing() throws Exception {
        String admin = login("root");
        int versionsBefore = jdbc.queryForObject("select count(*) from definition_version", Integer.class);
        int auditBefore = jdbc.queryForObject("select count(*) from audit_log", Integer.class);

        JsonNode ok = json(validate(admin, grievance), 200);
        assertThat(ok.isArray() && ok.isEmpty()).isTrue();
        JsonNode bad = mapper.readTree(grievance);
        ((com.fasterxml.jackson.databind.node.ObjectNode) bad.get("workflows").get(0)).put("startStep", "NOPE");
        assertThat(json(validate(admin, bad.toString()), 200)).isNotEmpty();
        JsonNode malformed = json(validate(admin, "{\n \"app\": ,"), 400);
        assertThat(malformed.get("line").asInt()).isEqualTo(2);
        assertThat(malformed.get("column").asInt()).isGreaterThanOrEqualTo(1);
        json(validate(admin, "[]"), 400);
        json(validate(admin, ""), 400);
        json(validate(login("boss"), grievance), 403);
        json(validate(null, grievance), 401);
        json(validate(admin, "x".repeat(BoundedBody.MAX_BYTES + 1)), 413);

        assertThat(jdbc.queryForObject("select count(*) from definition_version", Integer.class))
                .isEqualTo(versionsBefore);
        assertThat(jdbc.queryForObject("select count(*) from audit_log", Integer.class)).isEqualTo(auditBefore);
    }

    private MockHttpServletRequestBuilder validate(String auth, String body) {
        MockHttpServletRequestBuilder b = post("/api/definitions/validate")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return auth == null ? b : b.header("Authorization", auth);
    }

    // ---- record summaries and names -----------------------------------------------------------------------

    @Test
    void recordSummaryCasesOnListAndRead() throws Exception {
        String boss = login("boss");
        String base = "/api/apps/GRIEVANCE/entities/GRIEVANCE/records";

        WorkflowService.Started running = start("running");
        WorkflowService.Started ended = start("ended");
        workflows.execute(ended.instance().id(), "REJECT", Map.of(), "no", null, clerk());
        WorkflowService.Started queued = start("queued");
        workflows.execute(queued.instance().id(), "VERIFY", Map.of(), null, null, clerk());
        WorkflowService.Started unknown = start("unknown");
        UUID ghost = UUID.randomUUID();
        jdbc.update("update workflow_instance set assignee = ? where id = ?", ghost, unknown.instance().id());
        UUID plain = records.create("GRIEVANCE", "GRIEVANCE", payload("plain"), Principal.anonymous("PUBLIC")).id();

        JsonNode list = json(get(base + "?size=100").header("Authorization", boss), 200);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        list.get("items").forEach(i -> byId.put(i.get("id").asText(), i));
        for (boolean viaRead : new boolean[] {false, true}) {
            java.util.function.Function<UUID, JsonNode> view = id -> {
                try {
                    return viaRead ? json(get(base + "/" + id).header("Authorization", boss), 200)
                            : byId.get(id.toString());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            JsonNode r = view.apply(running.record().id());
            assertThat(r.get("step").asText()).isEqualTo("TRIAGE");
            assertThat(r.get("stepLabel").asText()).isEqualTo("Triage");
            assertThat(r.get("statusColor").asText()).isEqualTo("#64748b");
            assertThat(r.get("assignee").asText()).isEqualTo(idOf("clerk1").toString());
            assertThat(r.get("assigneeName").asText()).isEqualTo("clerk1");
            assertThat(r.get("dueAt").isNull()).isFalse();
            assertThat(r.get("ended").asBoolean()).isFalse();

            JsonNode e = view.apply(ended.record().id());
            assertThat(e.get("ended").asBoolean()).isTrue();
            assertThat(e.get("status").asText()).isEqualTo("Rejected");
            assertThat(e.get("assignee").isNull()).isTrue();
            assertThat(e.get("assigneeName").isNull()).isTrue();
            assertThat(e.get("dueAt").isNull()).isTrue();

            JsonNode q = view.apply(queued.record().id());
            assertThat(q.get("step").asText()).isEqualTo("REVIEW");
            assertThat(q.get("assignee").isNull()).isTrue();
            assertThat(q.get("assigneeName").isNull()).isTrue();

            JsonNode u = view.apply(unknown.record().id());
            assertThat(u.get("assignee").asText()).isEqualTo(ghost.toString());
            assertThat(u.get("assigneeName").isNull()).isTrue();

            JsonNode p = view.apply(plain);
            assertThat(p.get("instanceId").isNull()).isTrue();
            assertThat(p.get("stepLabel").isNull()).isTrue();
            assertThat(p.get("ended").asBoolean()).isFalse();
        }
    }

    @Test
    void historyAndInstanceViewCarryDisplayNames() throws Exception {
        String boss = login("boss");
        WorkflowService.Started s = start("names");
        workflows.execute(s.instance().id(), "VERIFY", Map.of(), "ok", null, clerk());
        JsonNode rows = json(get("/api/instances/" + s.instance().id() + "/history").header("Authorization", boss), 200);
        JsonNode action = null;
        for (JsonNode r : rows) {
            if ("ACTION".equals(r.get("kind").asText())) {
                action = r;
            }
            if ("START".equals(r.get("kind").asText())) {
                assertThat(r.get("actorName").isNull()).isTrue(); // public start: no actor
            }
        }
        assertThat(action).isNotNull();
        assertThat(action.get("actorName").asText()).isEqualTo("clerk1");

        WorkflowService.Started running = start("view");
        JsonNode view = json(get("/api/instances/" + running.instance().id()).header("Authorization", boss), 200);
        assertThat(view.get("assigneeName").asText()).isEqualTo("clerk1");
    }

    // ---- statement counts ---------------------------------------------------------------------------------

    @Test
    void recordListStatementCountDoesNotDependOnThePageSize() throws Exception {
        String boss = login("boss");
        for (int i = 0; i < 50; i++) {
            start("bulk " + i);
        }
        String base = "/api/apps/GRIEVANCE/entities/GRIEVANCE/records";
        statementsFor(base + "?size=1", boss); // warm the definition cache
        int one = statementsFor(base + "?size=1", boss);
        int fifty = statementsFor(base + "?size=50", boss);
        assertThat(fifty).isEqualTo(one);
        JsonNode page = json(get(base + "?size=50").header("Authorization", boss), 200);
        assertThat(page.get("items")).hasSize(50);
    }

    @Test
    void historyIssuesOneUserStatementForOneOrTwoHundredEntries() throws Exception {
        String boss = login("boss");
        WorkflowService.Started s = workflows.start("GRIEVANCE", "GRIEVANCE_FLOW", payload("history"), clerk());
        UUID id = s.instance().id();
        String url = "/api/instances/" + id + "/history";

        int small = userStatements(url, boss);
        assertThat(small).isEqualTo(1);

        UUID clerkId = idOf("clerk1");
        UUID bossId = idOf("boss");
        Instant t = Instant.now();
        for (int i = 0; i < 199; i++) {
            history.append(id, new HistoryEntry("EVENT_FAILED", null, null, null, null, null,
                    i % 2 == 0 ? clerkId : bossId, null, null, null, t.plusMillis(i), 0));
        }
        assertThat(json(get(url).header("Authorization", boss), 200)).hasSize(200);
        assertThat(userStatements(url, boss)).isEqualTo(1);
    }

    private int userStatements(String url, String auth) {
        int before = SQL.size();
        try {
            json(get(url).header("Authorization", auth), 200);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return (int) SQL.subList(before, SQL.size()).stream()
                .filter(s -> s.toLowerCase().contains("from app_user")).count();
    }
}
