package dev.skeletonkey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.app.InstanceQueryService;
import dev.skeletonkey.app.WorkflowService;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.config.HandlerRegistry;
import dev.skeletonkey.config.SecurityConfig;
import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.domain.expr.ExpressionEngine;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Instance endpoints: action, Instance_View, history (Req 9.1, 12.5-12.7, 15.1-15.7). */
@SpringJUnitWebConfig(InstanceEndpointsTest.Cfg.class)
class InstanceEndpointsTest {
    static final String SECRET = "0123456789abcdef0123456789abcdef-test-secret";
    static final java.util.concurrent.atomic.AtomicInteger LATEST = new java.util.concurrent.atomic.AtomicInteger(1);
    static final UUID CLERK = new UUID(0, 1);
    static final Principal STARTER = new Principal(new UUID(0, 99), Set.of("CITIZEN"));

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, InstanceController.class, ApiExceptionHandler.class})
    static class Cfg {
        @Bean SkeletonProperties props() {
            return new SkeletonProperties(new SkeletonProperties.Jwt(SECRET, 3600), null, null, null);
        }
        @Bean JwtService jwtService(SkeletonProperties p) { return new JwtService(p); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }

        @Bean ObjectNode rootV1() throws Exception {
            return (ObjectNode) new ObjectMapper()
                    .readTree(Files.readString(Path.of("../../examples/grievance.app.json"), StandardCharsets.UTF_8));
        }

        /** Version 1 is the example; version 2 drops the REJECT action of TRIAGE. LATEST selects which one is latest. */
        @Bean PublishedDefinitions defs(ObjectNode rootV1) {
            PublishedApp v1 = new PublishedApp(1, new DefinitionParser().parse(rootV1));
            ObjectNode copy = rootV1.deepCopy();
            ((ArrayNode) copy.get("workflows").get(0).get("steps").get(0).get("actions")).remove(1);
            PublishedApp v2 = new PublishedApp(2, new DefinitionParser().parse(copy));
            return new PublishedDefinitions() {
                @Override public Optional<PublishedApp> latestPublished(String appCode) {
                    return Optional.of(LATEST.get() == 2 ? v2 : v1);
                }
                @Override public Optional<PublishedApp> publishedVersion(String appCode, int version) {
                    return version == 1 ? Optional.of(v1) : version == 2 ? Optional.of(v2) : Optional.empty();
                }
            };
        }

        @Bean InMemoryRecordRepository records() { return new InMemoryRecordRepository(); }
        @Bean InMemoryInstanceRepository instances() { return new InMemoryInstanceRepository(); }
        @Bean InMemoryHistoryRepository histories() { return new InMemoryHistoryRepository(); }
        @Bean InMemoryUserRepository users() {
            InMemoryUserRepository users = new InMemoryUserRepository();
            users.insert(new UserAccount(CLERK, "clerk", "x", "Clerk", true, Set.of("CLERK")));
            return users;
        }

        @Bean WorkflowService workflowService(PublishedDefinitions defs, InMemoryRecordRepository records,
                                              InMemoryInstanceRepository instances, InMemoryHistoryRepository hist,
                                              InMemoryUserRepository users) {
            Clock clock = Clock.systemUTC();
            InMemoryAssignmentCursorRepository cursors = new InMemoryAssignmentCursorRepository();
            AuditService audit = new AuditService(new InMemoryAuditRepository(), clock);
            ExpressionEngine engine = new ExpressionEngine();
            HandlerRegistry registry = new HandlerRegistry(Map.of());
            EventRunner events = new EventRunner(engine, registry);
            TransitionSelector selector = new TransitionSelector(engine);
            Router router = new Router(engine, new RouterGuards(engine), new ActionStages(registry, events, selector),
                    selector, events, new AssigneeResolver(users, instances, cursors), registry);
            return new WorkflowService(defs, records, instances, hist, cursors, new InMemoryReferenceCounter(),
                    audit, router, clock);
        }

        @Bean InstanceQueryService queryService(PublishedDefinitions defs, InMemoryInstanceRepository instances,
                                                InMemoryRecordRepository records, InMemoryHistoryRepository hist,
                                                WorkflowService wf, InMemoryUserRepository users) {
            return new InstanceQueryService(defs, instances, records, hist, new RouterGuards(new ExpressionEngine()),
                    wf, users);
        }
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JwtService jwt;
    @Autowired WorkflowService workflow;
    @Autowired dev.skeletonkey.persistence.memory.InMemoryUserRepository users;
    @Autowired InMemoryInstanceRepository instanceStore;
    @Autowired InMemoryHistoryRepository historyStore;
    MockMvc mvc;
    UUID id;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        LATEST.set(1);
        Map<String, Object> payload = Map.of("subject", "Pothole", "details", "Deep", "category", "ROAD",
                "contactEmail", "a@b.co");
        WorkflowService.Started s = workflow.start("GRIEVANCE", "GRIEVANCE_FLOW", payload, STARTER);
        id = s.instance().id();
    }

    private String token(UUID user, String... roles) {
        return "Bearer " + jwt.issue(new UserAccount(user, "u", "x", "u", true, Set.of(roles)));
    }

    private String clerk() { return token(CLERK, "CLERK"); }

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Test
    void viewShowsStepStatusActionsAndFieldRulesForTheAssignee() throws Exception {
        mvc.perform(get("/api/instances/" + id).header("Authorization", clerk()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId", is(id.toString())))
                .andExpect(jsonPath("$.step", is("TRIAGE")))
                .andExpect(jsonPath("$.assignee", is(CLERK.toString())))
                .andExpect(jsonPath("$.assigneeName", is("Clerk")))
                .andExpect(jsonPath("$.status", is("Received")))
                .andExpect(jsonPath("$.ended", is(false)))
                .andExpect(jsonPath("$.fieldRules.category", is("required")))
                .andExpect(jsonPath("$.fieldRules.urgent", is("optional")))
                .andExpect(jsonPath("$.fieldRules.subject", is("readonly")));
    }

    @Test
    void viewIsEvaluatedAgainstTheInstancesPinnedVersionNotTheLatest() throws Exception {
        LATEST.set(2); // v2 has no REJECT, but the instance is pinned to v1
        mvc.perform(get("/api/instances/" + id).header("Authorization", clerk()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[*].code", contains("VERIFY", "REJECT")));
    }

    @Test
    void viewOmitsActionsTheCallerMayNotRun() throws Exception {
        mvc.perform(get("/api/instances/" + id).header("Authorization", token(new UUID(0, 5), "OFFICER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions", empty()));
    }

    @Test
    void unknownInstanceIs404() throws Exception {
        mvc.perform(get("/api/instances/" + UUID.randomUUID()).header("Authorization", clerk()))
                .andExpect(status().isNotFound());
    }

    @Test
    void notVisibleInstanceIs404ForViewAndHistory() throws Exception {
        String stranger = token(new UUID(0, 7), "CITIZEN_X");
        mvc.perform(get("/api/instances/" + id).header("Authorization", stranger)).andExpect(status().isNotFound());
        mvc.perform(get("/api/instances/" + id + "/history").header("Authorization", stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(get("/api/instances/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    void actionRunsThePipelineAndReturnsTheNewView() throws Exception {
        mvc.perform(post("/api/instances/" + id + "/actions/VERIFY").header("Authorization", clerk())
                        .contentType(JSON).content("{\"fields\":{\"category\":\"OTHER\"},\"remarks\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step", is("REVIEW")))
                .andExpect(jsonPath("$.status", is("Under review")));
    }

    @Test
    void actionErrorsUseTheEngineCodes() throws Exception {
        mvc.perform(post("/api/instances/" + id + "/actions/NOPE").header("Authorization", clerk())
                        .contentType(JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ACTION_NOT_AVAILABLE")));
        mvc.perform(post("/api/instances/" + id + "/actions/VERIFY")
                        .header("Authorization", token(new UUID(0, 5), "OFFICER")).contentType(JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("INSUFFICIENT_ROLE")));
        mvc.perform(post("/api/instances/" + UUID.randomUUID() + "/actions/VERIFY")
                        .header("Authorization", clerk()).contentType(JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void endedInstanceHasNoActions() throws Exception {
        mvc.perform(post("/api/instances/" + id + "/actions/REJECT").header("Authorization", clerk())
                        .contentType(JSON).content("{\"remarks\":\"no\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ended", is(true)))
                .andExpect(jsonPath("$.actions", empty()));
    }

    @Test
    void historyIsReturnedInOrder() throws Exception {
        mvc.perform(post("/api/instances/" + id + "/actions/VERIFY").header("Authorization", clerk())
                .contentType(JSON).content("{}")).andExpect(status().isOk());
        // After VERIFY the clerk no longer sees the instance (not assignee, creator or a view role); an officer does.
        mvc.perform(get("/api/instances/" + id + "/history")
                        .header("Authorization", token(new UUID(0, 5), "OFFICER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind", is("START")))
                .andExpect(jsonPath("$[*].kind", org.hamcrest.Matchers.hasItem("ACTION")))
                .andExpect(jsonPath("$[?(@.kind=='ACTION')].actionCode", contains("VERIFY")));
    }

    @Test
    void modifyingHistoryIs405NotSupported() throws Exception {
        String auth = clerk();
        String url = "/api/instances/" + id + "/history";
        for (var req : new org.springframework.test.web.servlet.RequestBuilder[] {
                put(url).header("Authorization", auth).contentType(JSON).content("{}"),
                patch(url).header("Authorization", auth).contentType(JSON).content("{}"),
                delete(url).header("Authorization", auth),
                post(url).header("Authorization", auth).contentType(JSON).content("{}")}) {
            mvc.perform(req).andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code", is("NOT_SUPPORTED")));
        }
    }

    @Test
    void assigneeNameIsNullWhenNobodyIsAssignedAndWhenTheUserIsUnknown() throws Exception {
        // VERIFY moves to REVIEW, a ROLE_QUEUE step: no assignee.
        mvc.perform(post("/api/instances/" + id + "/actions/VERIFY").header("Authorization", clerk())
                .contentType(JSON).content("{}")).andExpect(status().isOk());
        String officer = token(new UUID(0, 5), "OFFICER");
        mvc.perform(get("/api/instances/" + id).header("Authorization", officer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.assigneeName").value(org.hamcrest.Matchers.nullValue()));
        // An assignee id without a user row keeps the id and has no name.
        UUID ghost = UUID.randomUUID();
        InstanceState s = instanceStore.findById(id).orElseThrow();
        instanceStore.update(new InstanceState(s.id(), s.recordId(), s.appCode(), s.defVersion(), s.workflowCode(),
                s.step(), s.status(), ghost, s.dueAt(), s.ended(), s.stepEnteredAt(), s.lockVersion(),
                s.startedBy(), s.startedAt()));
        mvc.perform(get("/api/instances/" + id).header("Authorization", officer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee", is(ghost.toString())))
                .andExpect(jsonPath("$.assigneeName").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void historyCarriesActorNamesResolvedAtReadTime() throws Exception {
        mvc.perform(post("/api/instances/" + id + "/actions/VERIFY").header("Authorization", clerk())
                .contentType(JSON).content("{}")).andExpect(status().isOk());
        java.time.Instant t = java.time.Instant.now().plusSeconds(60);
        historyStore.append(id, new dev.skeletonkey.domain.model.HistoryEntry("NOTE_SYSTEM", null, null, null, null,
                null, dev.skeletonkey.definition.DefinitionService.SYSTEM_ACTOR.userId(), null, null, null, t, 0));
        historyStore.append(id, new dev.skeletonkey.domain.model.HistoryEntry("NOTE_NOBODY", null, null, null, null,
                null, null, null, null, null, t.plusSeconds(1), 0));
        historyStore.append(id, new dev.skeletonkey.domain.model.HistoryEntry("NOTE_UNKNOWN", null, null, null, null,
                null, UUID.randomUUID(), null, null, null, t.plusSeconds(2), 0));
        String officer = token(new UUID(0, 5), "OFFICER");
        com.fasterxml.jackson.databind.JsonNode rows = readHistory(officer);
        assertThat(byKind(rows, "ACTION").get("actorName").asText()).isEqualTo("Clerk");
        assertThat(byKind(rows, "ACTION").get("actorId").asText()).isEqualTo(CLERK.toString());
        assertThat(byKind(rows, "START").get("actorName").isNull()).isTrue(); // starter has no user row
        assertThat(byKind(rows, "NOTE_SYSTEM").get("actorName").isNull()).isTrue();
        assertThat(byKind(rows, "NOTE_NOBODY").get("actorName").isNull()).isTrue();
        assertThat(byKind(rows, "NOTE_UNKNOWN").get("actorName").isNull()).isTrue();
        // A renamed user shows the new name on old entries.
        users.put(new UserAccount(CLERK, "clerk", "x", "Clara Clerk", true, Set.of("CLERK")));
        try {
            assertThat(byKind(readHistory(officer), "ACTION").get("actorName").asText()).isEqualTo("Clara Clerk");
        } finally {
            users.put(new UserAccount(CLERK, "clerk", "x", "Clerk", true, Set.of("CLERK")));
        }
    }

    private com.fasterxml.jackson.databind.JsonNode readHistory(String auth) throws Exception {
        return new ObjectMapper().readTree(mvc.perform(get("/api/instances/" + id + "/history")
                        .header("Authorization", auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static com.fasterxml.jackson.databind.JsonNode byKind(com.fasterxml.jackson.databind.JsonNode rows,
                                                                  String kind) {
        for (com.fasterxml.jackson.databind.JsonNode r : rows) {
            if (kind.equals(r.get("kind").asText())) {
                return r;
            }
        }
        throw new AssertionError("no history row of kind " + kind);
    }}
