package dev.skeletonkey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.app.AdminGuard;
import dev.skeletonkey.app.SeedLoader;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.config.SecurityConfig;
import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.definition.DefinitionQueryService;
import dev.skeletonkey.definition.DefinitionService;
import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.domain.model.DefinitionCache;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.model.Principal;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Definition endpoints require the Platform_Admin role (Req 6.9); a seed can grant it. */
@SpringJUnitWebConfig(DefinitionAdminAccessTest.Cfg.class)
class DefinitionAdminAccessTest {
    static final String SECRET = "0123456789abcdef0123456789abcdef-test-secret";

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, DefinitionController.class, ApiExceptionHandler.class})
    static class Cfg {
        @Bean SkeletonProperties props() {
            return new SkeletonProperties(new SkeletonProperties.Jwt(SECRET, 3600), null, null, null);
        }
        @Bean AdminGuard adminGuard(SkeletonProperties p) { return new AdminGuard(p); }
        @Bean JwtService jwtService(SkeletonProperties p) { return new JwtService(p); }
        @Bean UserRepository users() { return new InMemoryUserRepository(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean DefinitionRepository definitionRepository() { return new InMemoryDefinitionRepository(); }
        @Bean DefinitionQueryService definitionQueryService(DefinitionRepository repo) {
            return new DefinitionQueryService(repo);
        }
        @Bean DefinitionService definitionService(DefinitionRepository repo) {
            DefinitionParser parser = new DefinitionParser();
            return new DefinitionService(new DefinitionLoader(), repo, parser,
                    new DefinitionCache(parser), HandlerCatalog.empty(),
                    new AuditService(new InMemoryAuditRepository(), Clock.systemUTC()), Clock.systemUTC());
        }
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @TempDir Path tmp;
    MockMvc mvc;
    String grievance;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        grievance = Files.readString(Path.of("../../examples/grievance.app.json"), StandardCharsets.UTF_8);
    }

    private String token(String... roles) {
        return "Bearer " + jwt.issue(new UserAccount(UUID.randomUUID(), "u", "x", "u", true, Set.of(roles)));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String auth, String body) {
        return b.header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String createDraft() throws Exception {
        MvcResult r = mvc.perform(json(post("/api/definitions"), token("PLATFORM_ADMIN"), grievance))
                .andExpect(status().isCreated()).andReturn();
        return new ObjectMapper().readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void nonAdminGets403InApiErrorShapeOnEveryEndpoint() throws Exception {
        String id = createDraft();
        String user = token("AGENT", "OFFICER");
        MockHttpServletRequestBuilder[] requests = {
            json(post("/api/definitions"), user, grievance),
            json(post("/api/apps/GRIEVANCE/definitions/1/edit"), user, grievance),
            json(put("/api/definitions/" + id), user, grievance),
            get("/api/definitions/" + id).header("Authorization", user),
            get("/api/definitions/" + id + "/info").header("Authorization", user),
            post("/api/definitions/" + id + "/publish").header("Authorization", user),
        };
        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status", is(403)))
                    .andExpect(jsonPath("$.code", is("FORBIDDEN")))
                    .andExpect(jsonPath("$.message").exists());
        }
        // Nothing was changed by the rejected publish.
        mvc.perform(get("/api/definitions/" + id + "/info").header("Authorization", token("PLATFORM_ADMIN")))
                .andExpect(jsonPath("$.status", is("DRAFT")));
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(post("/api/definitions").contentType(MediaType.APPLICATION_JSON).content(grievance))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanSubmitEditReadAndPublish() throws Exception {
        String admin = token("PLATFORM_ADMIN");
        String id = createDraft();
        mvc.perform(get("/api/definitions/" + id).header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/definitions/" + id + "/info").header("Authorization", admin))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/apps/GRIEVANCE/definitions/1/edit"), admin, grievance))
                .andExpect(status().isCreated());
        mvc.perform(json(put("/api/definitions/" + id), admin, grievance)).andExpect(status().isCreated());
        mvc.perform(post("/api/definitions/" + id + "/publish").header("Authorization", admin))
                .andExpect(status().isOk());
    }

    @Test
    void seedCanGrantTheAdminRole() throws Exception {
        Path seed = tmp.resolve("seed.json");
        Files.writeString(seed, "{\"users\":[{\"username\":\"root\",\"password\":\"pw\","
                + "\"roles\":[\"PLATFORM_ADMIN\"]}]}");
        SkeletonProperties props = new SkeletonProperties(new SkeletonProperties.Jwt(SECRET, 3600), null,
                new SkeletonProperties.Seed(seed.toString()), null);
        new SeedLoader(props, users, encoder, new ObjectMapper()).run(new DefaultApplicationArguments());

        UserAccount root = users.findByUsername("root").orElseThrow();
        assertThat(root.roles()).containsExactly("PLATFORM_ADMIN");
        mvc.perform(json(post("/api/definitions"), "Bearer " + jwt.issue(root), grievance))
                .andExpect(status().isCreated());
    }
}
