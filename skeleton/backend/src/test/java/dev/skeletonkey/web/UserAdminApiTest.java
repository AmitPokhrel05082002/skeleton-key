package dev.skeletonkey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.app.AdminGuard;
import dev.skeletonkey.app.AuthService;
import dev.skeletonkey.app.RoleOptionsService;
import dev.skeletonkey.app.UserAdminService;
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
import dev.skeletonkey.persistence.UserRepository;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryDefinitionRepository;
import dev.skeletonkey.persistence.memory.InMemoryUserRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * User administration API with in-memory repositories: access control, validation, case-insensitive usernames,
 * self-lockout, role options, and immediate rejection of a deactivated user's token (Requirements 1 to 6, 5.5, 5.7).
 */
@SpringJUnitWebConfig(UserAdminApiTest.Cfg.class)
class UserAdminApiTest {
    static final String SECRET = "0123456789abcdef0123456789abcdef-user-admin-secret";
    static final String PW = "password-1";

    /** A clock the test can move. */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-17T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, UserAdminController.class, AuthController.class, ApiExceptionHandler.class})
    static class Cfg {
        @Bean SkeletonProperties props() {
            return new SkeletonProperties(new SkeletonProperties.Jwt(SECRET, 3600), null, null, null);
        }
        @Bean AdminGuard adminGuard(SkeletonProperties p) { return new AdminGuard(p); }
        @Bean JwtService jwtService(SkeletonProperties p) { return new JwtService(p); }
        @Bean InMemoryUserRepository users() { return new InMemoryUserRepository(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean ActiveUserCache activeUserCache(UserRepository users, MutableClock clock) {
            return new ActiveUserCache(users, 30, clock);
        }
        @Bean AuditService audit() { return new AuditService(new InMemoryAuditRepository(), Clock.systemUTC()); }
        @Bean DefinitionRepository definitionRepository() { return new InMemoryDefinitionRepository(); }
        @Bean DefinitionQueryService definitionQueryService(DefinitionRepository repo) {
            return new DefinitionQueryService(repo);
        }
        @Bean DefinitionService definitionService(DefinitionRepository repo, AuditService audit) {
            DefinitionParser parser = new DefinitionParser();
            return new DefinitionService(new DefinitionLoader(), repo, parser, new DefinitionCache(parser),
                    HandlerCatalog.empty(), audit, Clock.systemUTC());
        }
        @Bean RoleOptionsService roleOptionsService(DefinitionQueryService q, DefinitionService d, AdminGuard g) {
            return new RoleOptionsService(q, d, g);
        }
        @Bean UserAdminService userAdminService(UserRepository u, PasswordEncoder e, RoleOptionsService r,
                                                AdminGuard g, AuditService a,
                                                org.springframework.beans.factory.ObjectProvider<ActiveUserCache> c,
                                                SkeletonProperties p) {
            return new UserAdminService(u, e, r, g, a, c, p);
        }
        @Bean AuthService authService(UserRepository u, PasswordEncoder e, JwtService j) {
            return new AuthService(u, e, j);
        }
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JwtService jwt;
    @Autowired InMemoryUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired DefinitionService definitions;
    @Autowired MutableClock clock;
    MockMvc mvc;
    UserAccount root;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        if (definitions.latestPublished("GRIEVANCE").isEmpty()) {
            String json = Files.readString(Path.of("../../examples/grievance.app.json"), StandardCharsets.UTF_8);
            definitions.publish(definitions.submit(json).id(), DefinitionService.SYSTEM_ACTOR);
        }
        root = users.findByUsername("root").orElseGet(() -> {
            UserAccount u = new UserAccount(UUID.randomUUID(), "root", encoder.encode(PW), "Root", true,
                    Set.of("PLATFORM_ADMIN"));
            users.insert(u);
            return u;
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private String bearer(UserAccount u) {
        return "Bearer " + jwt.issue(u);
    }

    private String admin() {
        return bearer(root);
    }

    private MvcResult call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b,
                           String auth, Object body) throws Exception {
        if (auth != null) {
            b.header("Authorization", auth);
        }
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : mapper.writeValueAsString(body));
        }
        return mvc.perform(b).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString();
        return s.isBlank() ? null : mapper.readTree(s);
    }

    private JsonNode create(String username, String... roles) throws Exception {
        MvcResult r = call(post("/api/admin/users"), admin(),
                Map.of("username", username, "displayName", "Name " + username, "password", PW,
                        "roles", java.util.List.of(roles)));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r);
    }

    private int loginStatus(String username, String password) throws Exception {
        return call(post("/api/auth/login"), null, Map.of("username", username, "password", password))
                .getResponse().getStatus();
    }

    private UserAccount account(JsonNode view) {
        return users.findById(UUID.fromString(view.get("id").asText())).orElseThrow();
    }

    // ---- access control (Req 1) ---------------------------------------------------------------------------

    @Test
    void everyEndpointIs401WithoutTokenAnd403ForNonAdmins() throws Exception {
        UserAccount clerk = new UserAccount(UUID.randomUUID(), "clerk0", "x", "c", true, Set.of("CLERK"));
        users.insert(clerk);
        String id = root.id().toString();
        var requests = new java.util.function.Supplier[] {
            () -> get("/api/admin/users"), () -> get("/api/admin/users/role-options"),
            () -> get("/api/admin/users/" + id), () -> post("/api/admin/users"),
            () -> patch("/api/admin/users/" + id), () -> post("/api/admin/users/" + id + "/password"),
        };
        for (var supplier : requests) {
            @SuppressWarnings("unchecked")
            var b1 = (org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) supplier.get();
            assertThat(call(b1, null, "{}").getResponse().getStatus()).isEqualTo(401);
            @SuppressWarnings("unchecked")
            var b2 = (org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) supplier.get();
            MvcResult r = call(b2, bearer(clerk), "{}");
            assertThat(r.getResponse().getStatus()).isEqualTo(403);
            assertThat(body(r).get("code").asText()).isEqualTo("FORBIDDEN");
        }
        assertThat(users.findByUsername("clerk0").orElseThrow().displayName()).isEqualTo("c");
    }

    // ---- create (Req 3) -----------------------------------------------------------------------------------

    @Test
    void createReturns201WithoutAnySecretAndStoresAHash() throws Exception {
        MvcResult r = call(post("/api/admin/users"), admin(),
                Map.of("username", "  mira  ", "displayName", "Mira K", "password", PW, "roles", java.util.List.of("CLERK")));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JsonNode v = body(r);
        assertThat(r.getResponse().getHeader("Location")).isEqualTo("/api/admin/users/" + v.get("id").asText());
        assertThat(v.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "username", "displayName", "active", "roles", "createdAt");
        assertThat(v.get("username").asText()).isEqualTo("mira");
        assertThat(v.get("active").asBoolean()).isTrue();
        String text = r.getResponse().getContentAsString();
        assertThat(text).doesNotContain(PW).doesNotContain("$2");
        UserAccount stored = account(v);
        assertThat(stored.passwordHash()).isNotEqualTo(PW);
        assertThat(encoder.matches(PW, stored.passwordHash())).isTrue();
    }

    @Test
    void usernamesAreCaseInsensitiveForUniquenessAndLogin() throws Exception {
        create("Asha", "CLERK");
        MvcResult dup = call(post("/api/admin/users"), admin(),
                Map.of("username", "asha", "displayName", "Other", "password", PW));
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(dup).get("code").asText()).isEqualTo("USERNAME_TAKEN");
        for (String casing : new String[] {"Asha", "asha", "ASHA", "aShA"}) {
            assertThat(loginStatus(casing, PW)).as(casing).isEqualTo(200);
        }
        assertThat(loginStatus("ashaa", PW)).isEqualTo(401);
    }

    @Test
    void createValidationReportsEveryBadFieldWithoutEchoingThePassword() throws Exception {
        MvcResult r = call(post("/api/admin/users"), admin(),
                Map.of("username", "a b", "displayName", " ", "password", "short",
                        "roles", java.util.List.of("NOPE", "PUBLIC")));
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        JsonNode b = body(r);
        assertThat(b.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        var fields = new java.util.HashSet<String>();
        b.get("violations").forEach(x -> fields.add(x.get("field").asText() + ":" + x.get("rule").asText()));
        assertThat(fields).contains("username:PATTERN", "displayName:LENGTH", "password:LENGTH", "roles:UNKNOWN_ROLE");
        assertThat(r.getResponse().getContentAsString()).doesNotContain("short");
        assertThat(b.get("violations").toString()).contains("NOPE").contains("PUBLIC");
        assertThat(users.findByUsername("a b")).isEmpty();
    }

    @Test
    void passwordBoundsAreInBytes() throws Exception {
        for (String pw : new String[] {"1234567", "x".repeat(73), "\u00e9".repeat(37)}) {
            MvcResult r = call(post("/api/admin/users"), admin(),
                    Map.of("username", "pwuser", "displayName", "P", "password", pw));
            assertThat(r.getResponse().getStatus()).as(pw.length() + " chars").isEqualTo(422);
        }
        for (String pw : new String[] {"12345678", "x".repeat(72), "\u00e9".repeat(36)}) {
            MvcResult r = call(post("/api/admin/users"), admin(),
                    Map.of("username", "pw" + pw.length() + pw.charAt(0), "displayName", "P", "password", pw));
            assertThat(r.getResponse().getStatus()).as(pw.length() + " chars").isEqualTo(201);
        }
    }

    // ---- list and read (Req 2) ----------------------------------------------------------------------------

    @Test
    void listSearchFilterPageAndSort() throws Exception {
        create("lst-anna", "CLERK");
        create("lst-bob", "OFFICER");
        JsonNode carl = create("lst-carl", "CLERK");
        call(patch("/api/admin/users/" + carl.get("id").asText()), admin(), Map.of("active", false));

        JsonNode all = body(call(get("/api/admin/users?q=LST-&sort=username,desc"), admin(), null));
        assertThat(all.get("total").asInt()).isEqualTo(3);
        assertThat(all.get("sort").asText()).isEqualTo("username,desc");
        assertThat(all.get("items").get(0).get("username").asText()).isEqualTo("lst-carl");

        JsonNode clerks = body(call(get("/api/admin/users?q=lst-&role=CLERK&active=true"), admin(), null));
        assertThat(clerks.get("total").asInt()).isEqualTo(1);
        assertThat(clerks.get("items").get(0).get("username").asText()).isEqualTo("lst-anna");

        JsonNode page2 = body(call(get("/api/admin/users?q=lst-&size=2&page=1"), admin(), null));
        assertThat(page2.get("items")).hasSize(1);
        assertThat(page2.get("total").asInt()).isEqualTo(3);

        assertThat(call(get("/api/admin/users?q=%25"), admin(), null).getResponse().getContentAsString())
                .contains("\"total\":0");
        for (String bad : new String[] {"sort=password", "page=-1", "size=0", "size=abc", "sort=username,up"}) {
            MvcResult r = call(get("/api/admin/users?" + bad), admin(), null);
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
        }
        assertThat(call(get("/api/admin/users/" + UUID.randomUUID()), admin(), null).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(call(get("/api/admin/users/not-a-uuid"), admin(), null).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void roleOptionsListNonPublicRolesAndTheAdminRole() throws Exception {
        JsonNode options = body(call(get("/api/admin/users/role-options"), admin(), null));
        var codes = new java.util.ArrayList<String>();
        options.forEach(o -> codes.add(o.get("code").asText()));
        assertThat(codes).containsExactly("CLERK", "INSPECTOR", "OFFICER", "PLATFORM_ADMIN", "SUPERVISOR");
        options.forEach(o -> {
            if (o.get("code").asText().equals("PLATFORM_ADMIN")) {
                assertThat(o.get("apps")).isEmpty();
                assertThat(o.get("label").asText()).isEqualTo("Platform administrator");
            } else {
                assertThat(o.get("apps").get(0).asText()).isEqualTo("GRIEVANCE");
            }
        });
    }

    // ---- update, self protection, password (Req 4, 5.4) ---------------------------------------------------

    @Test
    void patchChangesOnlyWhatIsSentAndReplacesRoles() throws Exception {
        JsonNode u = create("upd-user", "CLERK", "OFFICER");
        String url = "/api/admin/users/" + u.get("id").asText();
        JsonNode r = body(call(patch(url), admin(), Map.of("roles", java.util.List.of("SUPERVISOR"))));
        assertThat(r.get("roles")).hasSize(1);
        assertThat(r.get("roles").get(0).asText()).isEqualTo("SUPERVISOR");
        assertThat(r.get("displayName").asText()).isEqualTo("Name upd-user");
        r = body(call(patch(url), admin(), Map.of("displayName", " New Name ")));
        assertThat(r.get("displayName").asText()).isEqualTo("New Name");
        assertThat(r.get("roles").get(0).asText()).isEqualTo("SUPERVISOR");
        assertThat(call(patch(url), admin(), "{}").getResponse().getStatus()).isEqualTo(200);

        MvcResult bad = call(patch(url), admin(), Map.of("username", "other", "roles", java.util.List.of("NOPE")));
        assertThat(bad.getResponse().getStatus()).isEqualTo(422);
        assertThat(bad.getResponse().getContentAsString()).contains("UNKNOWN_PROPERTY").contains("UNKNOWN_ROLE");
        assertThat(users.findByUsername("upd-user")).isPresent();
    }

    @Test
    void aRoleTheUserAlreadyHoldsStaysAcceptedEvenIfNoLongerAssignable() throws Exception {
        UserAccount legacy = new UserAccount(UUID.randomUUID(), "legacy", encoder.encode(PW), "Legacy", true,
                Set.of("RETIRED_ROLE", "CLERK"));
        users.insert(legacy);
        String url = "/api/admin/users/" + legacy.id();
        assertThat(call(patch(url), admin(), Map.of("roles", java.util.List.of("RETIRED_ROLE"))).getResponse()
                .getStatus()).isEqualTo(200);
        assertThat(call(patch(url), admin(), Map.of("roles", java.util.List.of("OTHER_RETIRED"))).getResponse()
                .getStatus()).isEqualTo(422);
    }

    @Test
    void anAdminCannotDeactivateThemselvesOrDropTheirAdminRole() throws Exception {
        String url = "/api/admin/users/" + root.id();
        for (Map<String, Object> patch : java.util.List.<Map<String, Object>>of(
                Map.of("active", false),
                Map.of("roles", java.util.List.of("CLERK")),
                Map.of("displayName", "Renamed", "active", false))) {
            MvcResult r = call(patch(url), admin(), patch);
            assertThat(r.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(r).get("code").asText()).isEqualTo("SELF_LOCKOUT");
        }
        UserAccount after = users.findById(root.id()).orElseThrow();
        assertThat(after.active()).isTrue();
        assertThat(after.displayName()).isEqualTo("Root");
        assertThat(after.roles()).contains("PLATFORM_ADMIN");
        // Keeping the admin role, or changing only the name, is allowed.
        assertThat(call(patch(url), admin(), Map.of("roles", java.util.List.of("PLATFORM_ADMIN", "CLERK")))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(call(patch(url), admin(), Map.of("displayName", "Root")).getResponse().getStatus())
                .isEqualTo(200);
        users.put(new UserAccount(root.id(), "root", after.passwordHash(), "Root", true, Set.of("PLATFORM_ADMIN")));
    }

    @Test
    void resetPasswordReplacesTheHashAndChecksLength() throws Exception {
        JsonNode u = create("pw-reset", "CLERK");
        String url = "/api/admin/users/" + u.get("id").asText() + "/password";
        assertThat(call(post(url), admin(), Map.of("password", "short")).getResponse().getStatus()).isEqualTo(422);
        assertThat(call(post(url), admin(), "{}").getResponse().getStatus()).isEqualTo(422);
        MvcResult ok = call(post(url), admin(), Map.of("password", "new-password-9"));
        assertThat(ok.getResponse().getStatus()).isEqualTo(204);
        assertThat(ok.getResponse().getContentAsString()).isEmpty();
        assertThat(loginStatus("pw-reset", "new-password-9")).isEqualTo(200);
        assertThat(loginStatus("pw-reset", PW)).isEqualTo(401);
        assertThat(call(post("/api/admin/users/" + UUID.randomUUID() + "/password"), admin(),
                Map.of("password", "new-password-9")).getResponse().getStatus()).isEqualTo(404);
    }

    // ---- deactivation (Req 5.1, 5.5) ----------------------------------------------------------------------

    @Test
    void deactivatedUserCannotLogInAndGetsTheSameAnswerAsAWrongPassword() throws Exception {
        JsonNode u = create("fired-login", "CLERK");
        call(patch("/api/admin/users/" + u.get("id").asText()), admin(), Map.of("active", false));
        MvcResult inactive = call(post("/api/auth/login"), null, Map.of("username", "fired-login", "password", PW));
        MvcResult wrong = call(post("/api/auth/login"), null, Map.of("username", "fired-login", "password", "nope-nope"));
        assertThat(inactive.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(inactive).get("code")).isEqualTo(body(wrong).get("code"));
        assertThat(body(inactive).get("message")).isEqualTo(body(wrong).get("message"));
    }

    @Test
    void aDeactivatedUsersExistingTokenIsRejectedOnTheNextRequest() throws Exception {
        JsonNode u = create("ops-admin", "PLATFORM_ADMIN");
        UserAccount ops = account(u);
        String opsToken = bearer(ops);
        String url = "/api/admin/users/" + ops.id();
        assertThat(call(get("/api/admin/users/role-options"), opsToken, null).getResponse().getStatus()).isEqualTo(200);

        assertThat(call(patch(url), admin(), Map.of("active", false)).getResponse().getStatus()).isEqualTo(200);
        MvcResult after = call(get("/api/admin/users/role-options"), opsToken, null);
        assertThat(after.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(after).get("code").asText()).isEqualTo("UNAUTHENTICATED");

        call(patch(url), admin(), Map.of("active", true));
        assertThat(call(get("/api/admin/users/role-options"), opsToken, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void withoutEvictionTheActiveFlagIsRereadAfterTheCacheTime() throws Exception {
        UserAccount ops = account(create("ops-direct", "PLATFORM_ADMIN"));
        String token = bearer(ops);
        assertThat(call(get("/api/admin/users/role-options"), token, null).getResponse().getStatus()).isEqualTo(200);
        // Changed behind the service's back (as another instance would): visible only after the cache time.
        users.put(new UserAccount(ops.id(), ops.username(), ops.passwordHash(), ops.displayName(), false, ops.roles()));
        clock.advance(Duration.ofSeconds(29));
        assertThat(call(get("/api/admin/users/role-options"), token, null).getResponse().getStatus()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(2));
        assertThat(call(get("/api/admin/users/role-options"), token, null).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aTokenOfAnUnknownUserIsRejected() throws Exception {
        UserAccount ghost = new UserAccount(UUID.randomUUID(), "ghost", "x", "g", true, Set.of("PLATFORM_ADMIN"));
        assertThat(call(get("/api/admin/users"), bearer(ghost), null).getResponse().getStatus()).isEqualTo(401);
    }
}
