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

    /**
     * GRVSCOPE: the grievance example with its form restricted to CLERK (not public), plus an AUDIT entity with a
     * workflow only the AUDITOR role touches (as action role and assignee) and one only SUPERVISOR-escalated.
     */
    private void seedScoped() throws Exception {
        if (definitions.latestPublished("GRVSCOPE").isPresent()) {
            return;
        }
        ObjectMapper m = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode doc =
                (com.fasterxml.jackson.databind.node.ObjectNode) m.readTree(grievance);
        ((com.fasterxml.jackson.databind.node.ObjectNode) doc.get("app")).put("code", "GRVSCOPE");
        com.fasterxml.jackson.databind.node.ObjectNode form =
                (com.fasterxml.jackson.databind.node.ObjectNode) doc.get("forms").get(0);
        form.remove("public");
        form.putArray("roles").add("CLERK");
        ((com.fasterxml.jackson.databind.node.ArrayNode) doc.get("roles")).addObject()
                .put("code", "AUDITOR").put("label", "Auditor");
        doc.set("entities", append(m, doc.get("entities"), m.readTree("""
                {"code":"AUDIT","label":"Audit","fields":[{"code":"title","label":"Title","type":"TEXT"}]}""")));
        doc.set("workflows", append(m, doc.get("workflows"), m.readTree("""
                {"code":"AUDIT_FLOW","label":"Audit","entity":"AUDIT","startStep":"CHECK",
                 "statuses":[{"label":"Open","category":"OPEN","color":"#112233"},{"label":"Closed","category":"DONE"}],
                 "steps":[{"code":"CHECK","label":"Check","type":"TASK","slaHours":5,"escalateTo":"SUPERVISOR",
                           "assignee":{"strategy":"ROLE_QUEUE","role":"AUDITOR"},
                           "actions":[{"code":"CLOSE","label":"Close","roles":["AUDITOR"],"to":"DONE","status":"Closed"}]},
                          {"code":"DONE","label":"Done","type":"END","outcome":"Closed"}]}""")));
        var v = definitions.submit(m.writeValueAsString(doc));
        definitions.publish(v.id(), DefinitionService.SYSTEM_ACTOR);
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode append(ObjectMapper m, JsonNode arr, JsonNode item) {
        com.fasterxml.jackson.databind.node.ArrayNode a = m.createArrayNode().addAll(
                (com.fasterxml.jackson.databind.node.ArrayNode) arr);
        a.add(item);
        return a;
    }

    private JsonNode scopedRead(String... roles) throws Exception {
        seedScoped();
        String body = mvc.perform(get("/api/apps/GRVSCOPE/definition").header("Authorization", token(roles)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    private static java.util.List<String> codes(JsonNode arr) {
        java.util.List<String> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(n.get("code").asText()));
        return out;
    }

    private static java.util.List<String> keys(JsonNode n) {
        java.util.List<String> out = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void workflowsAreReturnedOnlyWhenReachable() throws Exception {
        // CLERK: form and INBOX view -> GRIEVANCE entity -> GRIEVANCE_FLOW. Not AUDIT_FLOW (no role anywhere in it).
        JsonNode clerk = scopedRead("CLERK");
        assertThat(codes(clerk.get("workflows"))).containsExactly("GRIEVANCE_FLOW");
        assertThat(codes(clerk.get("entities"))).containsExactly("GRIEVANCE");
        // AUDITOR: action role + assignee role bring in AUDIT_FLOW and widen entities with AUDIT.
        JsonNode auditor = scopedRead("AUDITOR");
        assertThat(codes(auditor.get("workflows"))).containsExactly("AUDIT_FLOW");
        assertThat(codes(auditor.get("entities"))).containsExactly("AUDIT");
        // SUPERVISOR: views -> GRIEVANCE_FLOW; escalateTo on AUDIT_FLOW -> AUDIT_FLOW too.
        JsonNode supervisor = scopedRead("SUPERVISOR");
        assertThat(codes(supervisor.get("workflows"))).containsExactly("GRIEVANCE_FLOW", "AUDIT_FLOW");
        assertThat(codes(supervisor.get("entities"))).containsExactly("GRIEVANCE", "AUDIT");
        // Unrelated role: nothing.
        JsonNode other = scopedRead("OTHER");
        assertThat(other.get("workflows")).isEmpty();
        assertThat(other.get("entities")).isEmpty();
    }

    @Test
    void formThatStartsWorkflowMakesItReachable() throws Exception {
        // The example's form has no roles, so any authenticated caller may submit it (rule d).
        String body = mvc.perform(get("/api/apps/GRIEVANCE/definition").header("Authorization", token("OTHER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(codes(new ObjectMapper().readTree(body).get("workflows"))).containsExactly("GRIEVANCE_FLOW");
    }

    @Test
    void workflowStatusAndStepKeysAreAnAllowList() throws Exception {
        String body = mvc.perform(get("/api/apps/GRIEVANCE/definition").header("Authorization", token("SUPERVISOR")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode wf = new ObjectMapper().readTree(body).get("workflows").get(0);
        assertThat(keys(wf)).containsExactlyInAnyOrder("code", "label", "entity", "statuses", "steps");
        assertThat(wf.get("entity").asText()).isEqualTo("GRIEVANCE");
        assertThat(keys(wf.get("statuses").get(0))).containsExactlyInAnyOrder("label", "category", "color");
        assertThat(wf.get("statuses")).hasSize(5);
        assertThat(wf.get("steps")).hasSize(6);
        assertThat(codes(wf.get("steps"))).containsExactly(
                "TRIAGE", "REVIEW", "SITE_VISIT", "RESOLVE", "END_RESOLVED", "END_REJECTED");
        wf.get("steps").forEach(s -> assertThat(keys(s)).containsExactlyInAnyOrder("code", "label", "type"));
        // Remove the sections that legitimately carry these words (views, forms) and check the workflows only.
        String wfJson = wf.toString();
        for (String banned : new String[] {"transitions", "when", "skipWhen", "handler", "assignee", "actions",
                "onEnter", "onExit", "escalateTo", "slaHours", "startStep", "initialStatus", "outcome", "fields"}) {
            assertThat(wfJson).doesNotContain("\"" + banned + "\"");
        }
        // The whole response never carries them either (forms/views do not use these keys).
        for (String banned : new String[] {"transitions", "when", "skipWhen", "handler", "assignee", "actions",
                "onEnter", "onExit", "escalateTo", "slaHours", "startStep"}) {
            assertThat(body).doesNotContain("\"" + banned + "\"");
        }
    }

    @Test
    void statusColorIsOmittedWhenAbsent() throws Exception {
        JsonNode auditor = scopedRead("AUDITOR");
        JsonNode statuses = auditor.get("workflows").get(0).get("statuses");
        assertThat(keys(statuses.get(0))).containsExactlyInAnyOrder("label", "category", "color");
        assertThat(keys(statuses.get(1))).containsExactlyInAnyOrder("label", "category");
    }

    /** Publishes the grievance example under {@code code} with the given branding JSON (null = absent). */
    private void seedBranding(String code, String brandingJson, String widget) throws Exception {
        if (definitions.latestPublished(code).isPresent()) {
            return;
        }
        ObjectMapper m = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode doc =
                (com.fasterxml.jackson.databind.node.ObjectNode) m.readTree(grievance);
        com.fasterxml.jackson.databind.node.ObjectNode app = (com.fasterxml.jackson.databind.node.ObjectNode) doc.get("app");
        app.put("code", code);
        app.remove("branding");
        if (brandingJson != null) {
            app.set("branding", m.readTree(brandingJson));
        }
        if (widget != null) {
            doc.get("entities").get(0).get("fields").forEach(f ->
                    ((com.fasterxml.jackson.databind.node.ObjectNode) f).put("widget", widget));
        }
        var v = definitions.submit(m.writeValueAsString(doc));
        definitions.publish(v.id(), DefinitionService.SYSTEM_ACTOR);
    }

    private JsonNode body(String url, String auth) throws Exception {
        var req = get(url);
        if (auth != null) {
            req = req.header("Authorization", auth);
        }
        return new ObjectMapper().readTree(
                mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void brandingIsProjectedAsDeclaredInBothProjections() throws Exception {
        String[][] cases = {
                {"BRFULL", "{\"primaryColor\":\"#112233\",\"logo\":\"branding/logo.svg\",\"theme\":\"dark\"}"},
                {"BRPART", "{\"primaryColor\":\"#aabbcc\"}"},
                {"BREMPTY", "{}"},
                {"BRNONE", null}};
        for (String[] c : cases) {
            seedBranding(c[0], c[1], null);
            JsonNode expected = c[1] == null ? null : new ObjectMapper().readTree(c[1]);
            for (JsonNode app : new JsonNode[] {
                    body("/api/apps/" + c[0] + "/definition", token("CLERK")).get("app"),
                    body("/api/public/apps/" + c[0], null).get("app")}) {
                if (expected == null) {
                    assertThat(app.has("branding")).isFalse();
                } else {
                    assertThat(app.get("branding")).isEqualTo(expected);
                }
                assertThat(app.get("code").asText()).isEqualTo(c[0]);
                assertThat(app.has("name")).isTrue();
            }
        }
    }

    @Test
    void widgetIsProjectedOnFieldsInBothProjections() throws Exception {
        seedBranding("WIDGETAPP", null, "star-Rating_1");
        JsonNode restricted = body("/api/apps/WIDGETAPP/definition", token("CLERK"));
        restricted.get("entities").get(0).get("fields")
                .forEach(f -> assertThat(f.get("widget").asText()).isEqualTo("star-Rating_1"));
        JsonNode pub = body("/api/public/apps/WIDGETAPP", null);
        assertThat(pub.get("forms").get(0).get("fields")).isNotEmpty();
        pub.get("forms").get(0).get("fields")
                .forEach(f -> assertThat(f.get("widget").asText()).isEqualTo("star-Rating_1"));
        // A field without a widget carries no widget key.
        JsonNode plain = body("/api/apps/GRIEVANCE/definition", token("CLERK"));
        plain.get("entities").get(0).get("fields").forEach(f -> assertThat(f.has("widget")).isFalse());
    }

    @Test
    void publicSubsetKeysAreOnTheAllowList() throws Exception {
        seedBranding("BRFULL", "{\"primaryColor\":\"#112233\",\"logo\":\"l.svg\",\"theme\":\"dark\"}", null);
        JsonNode pub = body("/api/public/apps/BRFULL", null);
        assertThat(keys(pub)).isSubsetOf("version", "app", "forms");
        assertThat(keys(pub.get("app"))).isSubsetOf("code", "name", "description", "branding");
        assertThat(keys(pub.get("app").get("branding"))).isSubsetOf("primaryColor", "logo", "theme");
        pub.get("forms").forEach(f -> {
            assertThat(keys(f)).isSubsetOf("code", "label", "entity", "sections", "fields");
            f.get("fields").forEach(fd -> assertThat(keys(fd)).isSubsetOf("code", "label", "type", "required",
                    "help", "default", "options", "ref", "min", "max", "maxLength", "pattern", "accept",
                    "maxFiles", "widget"));
        });
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
