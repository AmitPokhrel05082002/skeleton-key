package dev.skeletonkey.app;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.config.SecurityConfig;
import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.UserRepository;
import dev.skeletonkey.persistence.memory.InMemoryUserRepository;
import dev.skeletonkey.web.ApiExceptionHandler;
import dev.skeletonkey.web.AuthController;
import dev.skeletonkey.web.JwtService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** GET /api/auth/me: exact shape, 401 cases, roles from the store (Req 1, 8.6). */
@SpringJUnitWebConfig(AuthMeTest.Cfg.class)
class AuthMeTest {
    static final String SECRET = "0123456789abcdef0123456789abcdef-test-secret";

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, AuthController.class, ApiExceptionHandler.class})
    static class Cfg {
        @Bean AuthService authService(UserRepository u, PasswordEncoder e, JwtService j) {
            return new AuthService(u, e, j);
        }
        @Bean SkeletonProperties props() {
            return new SkeletonProperties(new SkeletonProperties.Jwt(SECRET, 3600), null, null, null);
        }
        @Bean JwtService jwtService(SkeletonProperties p) { return new JwtService(p); }
        @Bean UserRepository users() { return new InMemoryUserRepository(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @Autowired WebApplicationContext ctx;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    MockMvc mvc;
    UserAccount stored;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        if (users.findByUsername("meuser").isEmpty()) {
            users.insert(new UserAccount(UUID.randomUUID(), "meuser", "hash", "Me User", true,
                    Set.of("SUPERVISOR", "CLERK")));
            users.insert(new UserAccount(UUID.randomUUID(), "meinactive", "hash", "Gone", false, Set.of("CLERK")));
        }
        stored = users.findByUsername("meuser").orElseThrow();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void returnsExactlyThreeKeysWithSortedRoles() throws Exception {
        String body = mvc.perform(get("/api/auth/me").header("Authorization", bearer(jwt.issue(stored))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(stored.id().toString())))
                .andExpect(jsonPath("$.displayName", is("Me User")))
                .andExpect(jsonPath("$.roles", contains("CLERK", "SUPERVISOR")))
                .andReturn().getResponse().getContentAsString();
        JsonNode json = new ObjectMapper().readTree(body);
        List<String> keys = new java.util.ArrayList<>();
        json.fieldNames().forEachRemaining(keys::add);
        org.assertj.core.api.Assertions.assertThat(keys).containsExactlyInAnyOrder("userId", "displayName", "roles");
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("passwordHash").doesNotContain("username").doesNotContain("token");
    }

    @Test
    void missingExpiredAndTamperedTokensGive401() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
        JwtService past = new JwtService(SECRET, 60, Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC));
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(past.issue(stored))))
                .andExpect(status().isUnauthorized());
        String t = jwt.issue(stored);
        String tampered = t.substring(0, t.length() - 2) + (t.endsWith("AA") ? "BB" : "AA");
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(tampered)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void inactiveAndDeletedUsersGive401() throws Exception {
        UserAccount inactive = users.findByUsername("meinactive").orElseThrow();
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(jwt.issue(inactive))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
        UserAccount deleted = new UserAccount(UUID.randomUUID(), "ghost", "hash", "Ghost", true, Set.of("CLERK"));
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(jwt.issue(deleted))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rolesComeFromTheStoreNotTheToken() throws Exception {
        UserAccount claimsAdmin = new UserAccount(stored.id(), stored.username(), "hash", "Stale Name", true,
                Set.of("PLATFORM_ADMIN"));
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(jwt.issue(claimsAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName", is("Me User")))
                .andExpect(jsonPath("$.roles", contains("CLERK", "SUPERVISOR")));
    }
}
