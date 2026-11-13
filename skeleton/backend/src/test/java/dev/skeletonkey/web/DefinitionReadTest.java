package dev.skeletonkey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.app.AdminGuard;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.config.SecurityConfig;
import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.definition.DefinitionReadService;
import dev.skeletonkey.definition.DefinitionService;
import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.domain.model.DefinitionCache;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.validator.HandlerCatalog;
import dev.skeletonkey.persistence.DefinitionRepository;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.UserRepository;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryDefinitionRepository;
import dev.skeletonkey.persistence.memory.InMemoryUserRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Role-restricted definition read and the anonymous Public_Subset (Req 16). */
@SpringJUnitWebConfig(DefinitionReadTest.Cfg.class)
class DefinitionReadTest {
    static final String SECRET = "0123456789abcdef0123456789abcdef-test-secret";

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, DefinitionReadController.class, ApiExceptionHandler.class})
    static class Cfg {
        @Bean SkeletonProperties props() {
            return new SkeletonProperties(new SkeletonProperties.Jwt(SECRET, 3600), null, null, null);
        }
        @Bean AdminGuard adminGuard(SkeletonProperties p) { return new AdminGuard(p); }
        @Bean JwtService jwtService(SkeletonProperties p) { return new JwtService(p); }
        @Bean UserRepository users() { return new InMemoryUserRepository(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean DefinitionRepository definitions() { return new InMemoryDefinitionRepository(); }
        @Bean DefinitionService definitionService(DefinitionRepository repo) {
            DefinitionParser parser = new DefinitionParser();
            return new DefinitionService(new DefinitionLoader(), repo, parser, new DefinitionCache(parser),
                    HandlerCatalog.empty(), new AuditService(new InMemoryAuditRepository(), Clock.systemUTC()),
                    Clock.systemUTC());
        }
        @Bean DefinitionReadService readService(DefinitionService s, DefinitionRepository repo) {
            return new DefinitionReadService(s, repo);
        }
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JwtService jwt;
    @Autowired DefinitionService definitions;
    MockMvc mvc;
    String grievance;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        grievance = Files.readString(Path.of("../../examples/grievance.app.json"), StandardCharsets.UTF_8);
        // Version 1 PUBLISHED, version 2 left as DRAFT. The in-memory repository is shared, so only seed once.
        if (definitions.latestPublished("GRIEVANCE").isEmpty()) {
            var v1 = definitions.submit(grievance);
            definitions.publish(v1.id(), DefinitionService.SYSTEM_ACTOR);
            definitions.submit(grievance);
        }
    }

    private String token(String... roles) {
        return "Bearer " + jwt.issue(new UserAccount(UUID.randomUUID(), "u", "x", "u", true, Set.of(roles)));
    }

    @Test
    void publicSubsetHasOnlyPublicFormsAndExposedFields() throws Exception {
        String body = mvc.perform(get("/api/public/apps/GRIEVANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forms", hasSize(1)))
                .andExpect(jsonPath("$.forms[0].code", is("REPORT_ISSUE")))
                .andExpect(jsonPath("$.forms[0].fields[*].code",
                        contains("subject", "category", "details", "urgent", "location", "photo", "contactEmail")))
                .andExpect(jsonPath("$.forms[0].fields[1].options", hasSize(4)))
                .andExpect(jsonPath("$.forms[0].fields[1].options[0].label", is("Roads & footpaths")))
                .andReturn().getResponse().getContentAsString();
        JsonNode root = new ObjectMapper().readTree(body);
        assertThat(root.has("workflows")).isFalse();
        assertThat(root.has("roles")).isFalse();
        assertThat(root.has("views")).isFalse();
        assertThat(root.has("entities")).isFalse();
        for (String leaked : new String[] {"inspectionNotes", "resolutionNotes", "GRIEVANCE_FLOW", "handler",
                "SUPERVISOR", "startsWorkflow"}) {
            assertThat(body).doesNotContain(leaked);
        }
    }

    @Test
    void publicSubsetUnknownAppIs404() throws Exception {
        mvc.perform(get("/api/public/apps/NOPE")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("NOT_FOUND")));
    }

    @Test
    void restrictedReadFiltersViewsByRole() throws Exception {
        mvc.perform(get("/api/apps/GRIEVANCE/definition").header("Authorization", token("SUPERVISOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.views[*].code", contains("BOARD", "ALL", "INBOX")))
                .andExpect(jsonPath("$.entities", hasSize(1)));
        mvc.perform(get("/api/apps/GRIEVANCE/definition").header("Authorization", token("CLERK")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views[*].code", contains("INBOX")))
                .andExpect(jsonPath("$.forms[*].code", contains("REPORT_ISSUE")));
    }

    @Test
    void principalWithNoMatchingRoleGetsNoRestrictedViewsAndNoReferencedEntitiesBeyondForms() throws Exception {
        mvc.perform(get("/api/apps/GRIEVANCE/definition").header("Authorization", token("OTHER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views", hasSize(0)))
                .andExpect(jsonPath("$.forms", hasSize(1)));
    }

    @Test
    void explicitPublishedVersionIsReturned() throws Exception {
        mvc.perform(get("/api/apps/GRIEVANCE/definition?version=1").header("Authorization", token("CLERK")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version", is(1)));
    }

    @Test
    void errorCodes() throws Exception {
        String auth = token("CLERK");
        mvc.perform(get("/api/apps/NOPE/definition").header("Authorization", auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/apps/GRIEVANCE/definition?version=99").header("Authorization", auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/apps/GRIEVANCE/definition?version=2").header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)));
        mvc.perform(get("/api/apps/GRIEVANCE/definition")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/apps/GRIEVANCE/definition").header("Authorization", auth + "x"))
                .andExpect(status().isUnauthorized());
    }
}
