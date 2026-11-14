package dev.skeletonkey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import dev.skeletonkey.persistence.DefinitionRepository;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryDefinitionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** GET /api/definitions/apps and GET /api/apps/{app}/definitions (Req 4). */
@SpringJUnitWebConfig(DefinitionListEndpointsTest.Cfg.class)
class DefinitionListEndpointsTest {
    static final String SECRET = "0123456789abcdef0123456789abcdef-test-secret";
    static final String TS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, DefinitionController.class, ApiExceptionHandler.class})
    static class Cfg implements WebMvcConfigurer {
        @Override
        public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
            JsonTestSupport.useProductionInstantFormat(converters);
        }
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
            return new DefinitionService(new DefinitionLoader(), repo, parser, new DefinitionCache(parser),
                    HandlerCatalog.empty(), new AuditService(new InMemoryAuditRepository(), Clock.systemUTC()),
                    Clock.systemUTC());
        }
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JwtService jwt;
    @Autowired DefinitionService definitions;
    MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        if (definitions.latestPublished("GRIEVANCE").isEmpty()) {
            String doc = Files.readString(Path.of("../../examples/grievance.app.json"), StandardCharsets.UTF_8);
            definitions.publish(definitions.submit(doc).id(), DefinitionService.SYSTEM_ACTOR); // v1 PUBLISHED
            definitions.submit(doc);                                                          // v2 DRAFT
            ObjectNode other = (ObjectNode) json.readTree(doc);
            ((ObjectNode) other.get("app")).put("code", "ZEBRA");
            definitions.submit(json.writeValueAsString(other));
            ((ObjectNode) other.get("app")).put("code", "ALPHA_1");
            definitions.submit(json.writeValueAsString(other));
        }
    }

    private String token(String... roles) {
        return "Bearer " + jwt.issue(new UserAccount(UUID.randomUUID(), "u", "x", "u", true, Set.of(roles)));
    }

    private String admin() { return token("PLATFORM_ADMIN"); }

    @Test
    void appListIsSortedAndComplete() throws Exception {
        mvc.perform(get("/api/definitions/apps").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]", is("ALPHA_1")))
                .andExpect(jsonPath("$[1]", is("GRIEVANCE")))
                .andExpect(jsonPath("$[2]", is("ZEBRA")))
                .andExpect(jsonPath("$.length()", is(3)));
    }

    @Test
    void versionListIsHighestFirstWithTimestampsAndNoDocument() throws Exception {
        String body = mvc.perform(get("/api/apps/GRIEVANCE/definitions").header("Authorization", admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(body);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(rows.get(1).get("version").asInt()).isEqualTo(1);
        assertThat(rows.get(0).get("status").asText()).isEqualTo("DRAFT");
        assertThat(rows.get(0).get("publishedAt").isNull()).isTrue();
        assertThat(rows.get(1).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(rows.get(1).get("publishedAt").asText()).matches(TS);
        for (JsonNode r : rows) {
            List<String> keys = new ArrayList<>();
            r.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).containsExactlyInAnyOrder("id", "appCode", "version", "status", "contentHash",
                    "createdAt", "publishedAt");
            assertThat(r.get("createdAt").asText()).matches(TS);
            assertThat(r.get("appCode").asText()).isEqualTo("GRIEVANCE");
        }
    }

    @Test
    void nonAdminGets403WithTheSameBodyForKnownAndUnknownApps() throws Exception {
        String user = token("OFFICER");
        String known = mvc.perform(get("/api/apps/GRIEVANCE/definitions").header("Authorization", user))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("FORBIDDEN")))
                .andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(get("/api/apps/NOPE/definitions").header("Authorization", user))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(known.replaceAll("\"requestId\":\"[^\"]*\",?", ""))
                .isEqualTo(unknown.replaceAll("\"requestId\":\"[^\"]*\",?", ""));
        mvc.perform(get("/api/definitions/apps").header("Authorization", user)).andExpect(status().isForbidden());
    }

    @Test
    void anonymousGets401() throws Exception {
        mvc.perform(get("/api/definitions/apps")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/apps/GRIEVANCE/definitions")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownAndMalformedCodesGive404WithTheSameMessage() throws Exception {
        String a = mvc.perform(get("/api/apps/NOPE/definitions").header("Authorization", admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message", is("App not found")))
                .andReturn().getResponse().getContentAsString();
        for (String bad : new String[] {"lower", "A-B", "1ABC", "A".repeat(65)}) {
            String b = mvc.perform(get("/api/apps/" + bad + "/definitions").header("Authorization", admin()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message", is("App not found")))
                    .andReturn().getResponse().getContentAsString();
            assertThat(b.replaceAll("\"requestId\":\"[^\"]*\",?", ""))
                    .isEqualTo(a.replaceAll("\"requestId\":\"[^\"]*\",?", ""));
        }
    }
}
