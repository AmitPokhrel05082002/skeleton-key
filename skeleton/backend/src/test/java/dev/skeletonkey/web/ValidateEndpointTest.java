package dev.skeletonkey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.app.AdminGuard;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.config.SecurityConfig;
import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.definition.DefinitionQueryService;
import dev.skeletonkey.definition.DefinitionService;
import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.domain.model.DefinitionCache;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.validator.HandlerCatalog;
import dev.skeletonkey.persistence.AuditEntry;
import dev.skeletonkey.persistence.DefinitionRepository;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryDefinitionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** POST /api/definitions/validate (Req 7). */
@SpringJUnitWebConfig(ValidateEndpointTest.Cfg.class)
class ValidateEndpointTest {
    static final String SECRET = "0123456789abcdef0123456789abcdef-test-secret";
    static final AtomicInteger WRITES = new AtomicInteger();

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, DefinitionController.class, ApiExceptionHandler.class})
    static class Cfg {
        @Bean SkeletonProperties props() {
            return new SkeletonProperties(new SkeletonProperties.Jwt(SECRET, 3600), null, null, null);
        }
        @Bean AdminGuard adminGuard(SkeletonProperties p) { return new AdminGuard(p); }
        @Bean JwtService jwtService(SkeletonProperties p) { return new JwtService(p); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean DefinitionRepository definitionRepository() { return new InMemoryDefinitionRepository(); }
        @Bean DefinitionQueryService queryService(DefinitionRepository repo) { return new DefinitionQueryService(repo); }
        @Bean DefinitionService definitionService(DefinitionRepository repo) {
            DefinitionParser parser = new DefinitionParser();
            InMemoryAuditRepository audit = new InMemoryAuditRepository() {
                @Override public synchronized void append(AuditEntry entry) {
                    WRITES.incrementAndGet();
                    super.append(entry);
                }
            };
            return new DefinitionService(new DefinitionLoader(), repo, parser, new DefinitionCache(parser),
                    HandlerCatalog.empty(), new AuditService(audit, Clock.systemUTC()), Clock.systemUTC());
        }
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JwtService jwt;
    @Autowired DefinitionRepository repo;
    MockMvc mvc;
    String grievance;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        grievance = Files.readString(Path.of("../../examples/grievance.app.json"), StandardCharsets.UTF_8);
        WRITES.set(0);
    }

    private String token(String... roles) {
        return "Bearer " + jwt.issue(new UserAccount(UUID.randomUUID(), "u", "x", "u", true, Set.of(roles)));
    }

    private MockHttpServletRequestBuilder validate(String auth, String body) {
        MockHttpServletRequestBuilder b = post("/api/definitions/validate")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return auth == null ? b : b.header("Authorization", auth);
    }

    private void assertNothingStored() {
        assertThat(repo.findAppCodes()).isEmpty();
        assertThat(WRITES.get()).isZero();
    }

    @Test
    void validDocumentGivesAnEmptyArray() throws Exception {
        mvc.perform(validate(token("PLATFORM_ADMIN"), grievance))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        assertNothingStored();
    }

    @Test
    void schemaViolationsAreAllReportedWithTheirPointer() throws Exception {
        ObjectNode doc = (ObjectNode) json.readTree(grievance);
        ((ObjectNode) doc.get("entities").get(0).get("fields").get(0)).put("widget", "9 bad");
        doc.remove("roles");
        mvc.perform(validate(token("PLATFORM_ADMIN"), doc.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$[?(@.field=='/entities/0/fields/0/widget')].rule").value(
                        org.hamcrest.Matchers.hasItem("SCHEMA")));
        assertNothingStored();
    }

    @Test
    void validatorViolationsAreReturnedLikePublish() throws Exception {
        ObjectNode doc = (ObjectNode) json.readTree(grievance);
        ((ObjectNode) doc.get("workflows").get(0)).put("startStep", "NO_SUCH_STEP");
        String body = mvc.perform(validate(token("PLATFORM_ADMIN"), doc.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(body);
        assertThat(rows.size()).isGreaterThanOrEqualTo(1);
        assertThat(rows.get(0).has("field")).isTrue();
        assertThat(rows.get(0).has("rule")).isTrue();
        assertNothingStored();
    }

    @Test
    void emptyMalformedAndNonObjectBodiesAre400() throws Exception {
        String admin = token("PLATFORM_ADMIN");
        mvc.perform(validate(admin, "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("MALFORMED_JSON")));
        mvc.perform(validate(admin, "{\n  \"app\": {\n    \"code\": ,\n"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("MALFORMED_JSON")))
                .andExpect(jsonPath("$.line", is(3)))
                .andExpect(jsonPath("$.column", greaterThanOrEqualTo(1)));
        mvc.perform(validate(admin, "[1,2,3]")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("BAD_REQUEST")));
        assertNothingStored();
    }

    @Test
    void nonAdminGets403EvenWithAnOversizedBody() throws Exception {
        mvc.perform(validate(token("OFFICER"), grievance)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN")));
        mvc.perform(validate(token("OFFICER"), "x".repeat(BoundedBody.MAX_BYTES + 1)))
                .andExpect(status().isForbidden());
        assertNothingStored();
    }

    @Test
    void anonymousGets401() throws Exception {
        mvc.perform(validate(null, grievance)).andExpect(status().isUnauthorized());
    }

    @Test
    void bodyOfOneMiBPlusOneByteIs413AndIsNotParsed() throws Exception {
        String admin = token("PLATFORM_ADMIN");
        // Not JSON: a parse attempt would give 400, so 413 shows the body was refused before parsing.
        mvc.perform(validate(admin, "x".repeat(BoundedBody.MAX_BYTES + 1)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code", is("PAYLOAD_TOO_LARGE")));
        assertNothingStored();
    }

    @Test
    void bodyOfExactlyOneMiBIsAccepted() throws Exception {
        // A valid JSON object padded to exactly 1 MiB: read in full, parsed, and rejected by the schema (200).
        String prefix = "{\"pad\":\"";
        String suffix = "\"}";
        String body = prefix + "x".repeat(BoundedBody.MAX_BYTES - prefix.length() - suffix.length()) + suffix;
        assertThat(body.getBytes(StandardCharsets.UTF_8)).hasSize(BoundedBody.MAX_BYTES);
        mvc.perform(validate(token("PLATFORM_ADMIN"), body)).andExpect(status().isOk());
        assertNothingStored();
    }
}
