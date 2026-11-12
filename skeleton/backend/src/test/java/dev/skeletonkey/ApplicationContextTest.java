package dev.skeletonkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.definition.DefinitionService;
import dev.skeletonkey.definition.DefinitionVersionRef;
import dev.skeletonkey.persistence.DefinitionVersionRow;
import dev.skeletonkey.record.PublishedDefinitions;
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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

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
    void grievanceDefinitionSubmitsAndPublishesAsSystemActor() throws IOException {
        // TODO(task 10.6): once StartupLoader (Auto_Publish ApplicationRunner) exists, point
        // skeletonkey.definitions.dir at a directory holding grievance.app.json and assert the published version
        // through it. Until then this performs exactly the calls Auto_Publish will make.
        String json = Files.readString(GRIEVANCE, StandardCharsets.UTF_8);
        DefinitionVersionRef draft = definitions.submit(json);
        assertThat(draft.status()).isEqualTo(DefinitionVersionRow.DRAFT);

        DefinitionVersionRef pub = definitions.publish(draft.id(), DefinitionService.SYSTEM_ACTOR);
        assertThat(pub.status()).isEqualTo("PUBLISHED");
        assertThat(pub.contentHash()).isNotBlank();

        var latest = published.latestPublished(pub.appCode());
        assertThat(latest).isPresent();
        assertThat(latest.get().version()).isGreaterThanOrEqualTo(pub.version());

        Integer audits = jdbc.queryForObject(
                "select count(*) from audit_log where action = ? and target_type = ? and target_id = ?",
                Integer.class, AuditService.PUBLISH, DefinitionService.AUDIT_TARGET, pub.id().toString());
        assertThat(audits).isEqualTo(1);
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

    private String login(String username, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = mapper.readTree(r.getResponse().getContentAsString());
        assertThat(node.get("token").asText()).isNotBlank();
        return node.get("token").asText();
    }
}
