package dev.skeletonkey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.app.FormSubmissionService;
import dev.skeletonkey.app.WorkflowService;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.config.HandlerRegistry;
import dev.skeletonkey.config.SecurityConfig;
import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.persistence.RecordRow;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.memory.InMemoryAssignmentCursorRepository;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryHistoryRepository;
import dev.skeletonkey.persistence.memory.InMemoryInstanceRepository;
import dev.skeletonkey.persistence.memory.InMemoryRecordRepository;
import dev.skeletonkey.persistence.memory.InMemoryReferenceCounter;
import dev.skeletonkey.persistence.memory.InMemoryUserRepository;
import dev.skeletonkey.record.PublishedDefinitions;
import dev.skeletonkey.record.PublishedDefinitions.PublishedApp;
import dev.skeletonkey.record.RecordService;
import dev.skeletonkey.router.ActionStages;
import dev.skeletonkey.router.AssigneeResolver;
import dev.skeletonkey.router.EventRunner;
import dev.skeletonkey.router.Router;
import dev.skeletonkey.router.RouterGuards;
import dev.skeletonkey.router.TransitionSelector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
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

/** Form submit endpoint (Req 6.5, 6.6, 8.1, 11.2). */
@SpringJUnitWebConfig(FormSubmitTest.Cfg.class)
class FormSubmitTest {
    static final String SECRET = "0123456789abcdef0123456789abcdef-test-secret";
    static final AtomicInteger INSERTS = new AtomicInteger();

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, FormController.class, ApiExceptionHandler.class})
    static class Cfg {
        @Bean SkeletonProperties props() {
            return new SkeletonProperties(new SkeletonProperties.Jwt(SECRET, 3600), null, null, null);
        }
        @Bean JwtService jwtService(SkeletonProperties p) { return new JwtService(p); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean FormSubmissionService formSubmissionService() throws Exception {
            ObjectNode root = (ObjectNode) new ObjectMapper()
                    .readTree(Files.readString(Path.of("../../examples/grievance.app.json"), StandardCharsets.UTF_8));
            ArrayNode forms = (ArrayNode) root.get("forms");
            forms.add(form("ANY_USER", null, null));
            forms.add(form("STAFF_ONLY", "CLERK", "GRIEVANCE_FLOW"));
            ((ObjectNode) forms.get(2)).remove("startsWorkflow");
            forms.add(form("STAFF_FLOW", "CLERK", "GRIEVANCE_FLOW"));
            PublishedApp app = new PublishedApp(1, new DefinitionParser().parse(root));
            PublishedDefinitions defs = a -> Optional.of(app);

            Clock clock = Clock.systemUTC();
            InMemoryRecordRepository records = new InMemoryRecordRepository() {
                @Override public void insert(RecordRow row) {
                    INSERTS.incrementAndGet();
                    super.insert(row);
                }
            };
            InMemoryInstanceRepository instances = new InMemoryInstanceRepository();
            InMemoryAssignmentCursorRepository cursors = new InMemoryAssignmentCursorRepository();
            InMemoryUserRepository users = new InMemoryUserRepository();
            users.insert(new UserAccount(new UUID(0, 1), "clerk", "x", "Clerk", true, Set.of("CLERK")));
            AuditService audit = new AuditService(new InMemoryAuditRepository(), clock);
            ExpressionEngine engine = new ExpressionEngine();
            HandlerRegistry registry = new HandlerRegistry(Map.of());
            EventRunner events = new EventRunner(engine, registry);
            TransitionSelector selector = new TransitionSelector(engine);
            Router router = new Router(engine, new RouterGuards(engine), new ActionStages(registry, events, selector),
                    selector, events, new AssigneeResolver(users, instances, cursors), registry);
            InMemoryReferenceCounter counter = new InMemoryReferenceCounter();
            WorkflowService wf = new WorkflowService(defs, records, instances, new InMemoryHistoryRepository(),
                    cursors, counter, audit, router, clock);
            RecordService rs = new RecordService(defs, records, instances, counter, audit, clock);
            return new FormSubmissionService(defs, wf, rs);
        }

        private static ObjectNode form(String code, String role, String workflow) {
            ObjectMapper m = new ObjectMapper();
            ObjectNode f = m.createObjectNode();
            f.put("code", code).put("entity", "GRIEVANCE");
            if (role != null) f.putArray("roles").add(role);
            if (workflow != null) f.put("startsWorkflow", workflow);
            ObjectNode section = f.putArray("sections").addObject();
            section.putArray("fields").add("subject").add("category").add("details").add("urgent").add("contactEmail");
            return f;
        }
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JwtService jwt;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        INSERTS.set(0);
    }

    private static final String BODY = "{\"subject\":\"Pothole\",\"details\":\"Deep\",\"category\":\"ROAD\","
            + "\"contactEmail\":\"a@b.co\",\"urgent\":false}";

    private String token(String... roles) {
        return "Bearer " + jwt.issue(new UserAccount(UUID.randomUUID(), "u", "x", "u", true, Set.of(roles)));
    }

    private MockHttpServletRequestBuilder submit(String form, String auth, String body) {
        MockHttpServletRequestBuilder b = post("/api/apps/GRIEVANCE/forms/" + form + "/submit")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return auth == null ? b : b.header("Authorization", auth);
    }

    @Test
    void publicFormAcceptedWithoutJwtAndResponseHoldsOnlyReferenceAndIds() throws Exception {
        String json = mvc.perform(submit("REPORT_ISSUE", null, BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.referenceNo", containsString("GRV-")))
                .andReturn().getResponse().getContentAsString();
        JsonNode n = new ObjectMapper().readTree(json);
        assertThat(n.fieldNames()).toIterable().containsExactlyInAnyOrder("recordId", "referenceNo", "instanceId");
        assertThat(json).doesNotContain("Pothole").doesNotContain("a@b.co");
        for (String summaryKey : new String[] {"stepLabel", "statusColor", "assigneeName", "ended", "dueAt"}) {
            assertThat(n.has(summaryKey)).as(summaryKey).isFalse();
        }
        assertThat(INSERTS.get()).isEqualTo(1);
    }

    @Test
    void publicFormWithInvalidTokenContinuesAsAnonymous() throws Exception {
        mvc.perform(submit("REPORT_ISSUE", "Bearer not-a-token", BODY)).andExpect(status().isCreated());
    }

    @Test
    void nonPublicFormWithoutJwtIs401AndStoresNothing() throws Exception {
        mvc.perform(submit("ANY_USER", null, BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)));
        assertThat(INSERTS.get()).isZero();
    }

    @Test
    void nonPublicFormWithoutRolesAcceptsAnyAuthenticatedPrincipal() throws Exception {
        mvc.perform(submit("ANY_USER", token("CITIZEN_X"), BODY)).andExpect(status().isCreated());
    }

    @Test
    void formWithRolesRejectsOtherPrincipalWith403AndStoresNoRecord() throws Exception {
        mvc.perform(submit("STAFF_FLOW", token("OFFICER"), BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)));
        assertThat(INSERTS.get()).isZero();
    }

    @Test
    void formWithRolesAcceptsPrincipalHoldingOne() throws Exception {
        mvc.perform(submit("STAFF_FLOW", token("CLERK"), BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instanceId").exists());
    }

    @Test
    void formWithoutWorkflowCreatesOnlyTheRecord() throws Exception {
        mvc.perform(submit("STAFF_ONLY", token("CLERK"), BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.referenceNo", containsString("GRV-")))
                .andExpect(jsonPath("$.instanceId").doesNotExist());
        assertThat(INSERTS.get()).isEqualTo(1);
    }

    @Test
    void keysOutsideTheFormAre422NamingThemAndNothingIsStored() throws Exception {
        String body = BODY.replace("}", ",\"resolutionNotes\":\"x\",\"hack\":1}");
        mvc.perform(submit("REPORT_ISSUE", null, body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[*].field").value(
                        org.hamcrest.Matchers.containsInAnyOrder("resolutionNotes", "hack")));
        assertThat(INSERTS.get()).isZero();
    }
}
