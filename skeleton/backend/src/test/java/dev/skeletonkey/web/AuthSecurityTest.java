package dev.skeletonkey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.config.SecurityConfig;
import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.UserRepository;
import dev.skeletonkey.persistence.memory.InMemoryUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(AuthSecurityTest.Cfg.class)
class AuthSecurityTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef-test-secret";

    @RestController
    static class Probe {
        @GetMapping("/api/protected") String protectedRoute() { return "ok"; }
        @PostMapping("/api/apps/a/forms/f/submit") String submit() { return "submitted"; }
        @GetMapping("/api/public/apps/a") String pub() { return "public"; }
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, AuthController.class, ApiExceptionHandler.class, Probe.class})
    static class Cfg {
        @Bean dev.skeletonkey.app.AuthService authService(UserRepository u, PasswordEncoder e, JwtService j) {
            return new dev.skeletonkey.app.AuthService(u, e, j);
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
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;
    MockMvc mvc;
    UserAccount alice;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        if (users.findByUsername("alice").isEmpty()) {
            alice = new UserAccount(UUID.randomUUID(), "alice", encoder.encode("secret"), "Alice", true, Set.of("AGENT"));
            users.insert(alice);
            users.insert(new UserAccount(UUID.randomUUID(), "bob", encoder.encode("secret"), "Bob", false, Set.of("AGENT")));
        } else {
            alice = users.findByUsername("alice").get();
        }
    }

    private static String body(String u, String p) {
        return "{\"username\":" + (u == null ? "null" : "\"" + u + "\"") + ",\"password\":" + (p == null ? "null" : "\"" + p + "\"") + "}";
    }

    @Test
    void loginReturnsTokenWithUserIdAndRoles() throws Exception {
        String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("alice", "secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andReturn().getResponse().getContentAsString();
        String token = new ObjectMapper().readTree(json).get("token").asText();
        Principal p = jwt.parse(token).orElseThrow();
        assertThat(p.userId()).isEqualTo(alice.id());
        assertThat(p.roles()).containsExactly("AGENT");
    }

    @Test
    void unknownUserWrongPasswordAndInactiveGiveIdentical401() throws Exception {
        String[] bodies = new String[3];
        String[] in = {body("nobody", "secret"), body("alice", "wrong"), body("bob", "secret")};
        for (int i = 0; i < 3; i++) {
            String r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(in[i]))
                    .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
            bodies[i] = r.replaceAll("\"requestId\":\"[^\"]*\",?", "");
        }
        assertThat(bodies[0]).isEqualTo(bodies[1]).isEqualTo(bodies[2]);
    }

    @Test
    void blankOrOversizedInputIs400NamingTheField() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("", "secret")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field", is("username")));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("alice", "x".repeat(257))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field", is("password")));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body(null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.length()", is(2)));
    }

    @Test
    void protectedRouteNeedsValidToken() throws Exception {
        mvc.perform(get("/api/protected")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
        mvc.perform(get("/api/protected").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
        String tampered = jwt.issue(alice);
        tampered = tampered.substring(0, tampered.length() - 2) + (tampered.endsWith("AA") ? "BB" : "AA");
        mvc.perform(get("/api/protected").header("Authorization", "Bearer " + tampered)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/protected").header("Authorization", "Bearer " + jwt.issue(alice))).andExpect(status().isOk());
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService past = new JwtService(SECRET, 60, Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC));
        assertThat(jwt.parse(past.issue(alice))).isEmpty();
    }

    @Test
    void tokenSignedWithOtherSecretIsRejected() {
        JwtService other = new JwtService("another-secret-another-secret-another!!", 60, Clock.systemUTC());
        assertThat(jwt.parse(other.issue(alice))).isEmpty();
    }

    @Test
    void anonymousRoutesAreOpen() throws Exception {
        mvc.perform(get("/api/public/apps/a")).andExpect(status().isOk());
        mvc.perform(post("/api/apps/a/forms/f/submit")).andExpect(status().isOk());
    }
}
