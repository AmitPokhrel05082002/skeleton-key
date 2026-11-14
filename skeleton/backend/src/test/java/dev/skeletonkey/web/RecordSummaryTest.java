package dev.skeletonkey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.config.SecurityConfig;
import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.persistence.UserAccount;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryInstanceRepository;
import dev.skeletonkey.persistence.memory.InMemoryRecordRepository;
import dev.skeletonkey.persistence.memory.InMemoryReferenceCounter;
import dev.skeletonkey.persistence.memory.InMemoryUserRepository;
import dev.skeletonkey.record.PublishedDefinitions;
import dev.skeletonkey.record.PublishedDefinitions.PublishedApp;
import dev.skeletonkey.record.RecordItemAssembler;
import dev.skeletonkey.record.RecordService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
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

/** Workflow summary on the record endpoints (Req 2): the five cases on list and read, plus create/replace/patch. */
@SpringJUnitWebConfig(RecordSummaryTest.Cfg.class)
class RecordSummaryTest {
    static final String SECRET = "0123456789abcdef0123456789abcdef-test-secret";
    static final UUID VIEWER = new UUID(0, 42);
    static final UUID KNOWN = new UUID(0, 43);
    static final String BASE = "/api/apps/GRIEVANCE/entities/GRIEVANCE/records";
    static final String[] SUMMARY_KEYS = {"instanceId", "step", "stepLabel", "status", "statusColor", "assignee",
            "assigneeName", "dueAt", "ended"};

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, RecordController.class, ApiExceptionHandler.class})
    static class Cfg implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
        @Override
        public void extendMessageConverters(java.util.List<org.springframework.http.converter.HttpMessageConverter<?>> converters) {
            for (var c : converters) {
                if (c instanceof org.springframework.http.converter.json.MappingJackson2HttpMessageConverter j) {
                    j.setObjectMapper(new ObjectMapper().registerModule(dev.skeletonkey.config.JacksonConfig.instantModule())
                            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
                }
            }
        }
        @Bean SkeletonProperties props() {
            return new SkeletonProperties(new SkeletonProperties.Jwt(SECRET, 3600), null, null, null);
        }
        @Bean JwtService jwtService(SkeletonProperties p) { return new JwtService(p); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean InMemoryRecordRepository records() { return new InMemoryRecordRepository(); }
        @Bean InMemoryInstanceRepository instances() { return new InMemoryInstanceRepository(); }
        @Bean InMemoryUserRepository users() {
            InMemoryUserRepository u = new InMemoryUserRepository();
            u.insert(new UserAccount(KNOWN, "known", "x", "Known Person", true, Set.of("CLERK")));
            return u;
        }
        @Bean PublishedDefinitions defs() throws Exception {
            ObjectMapper m = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) m
                    .readTree(Files.readString(Path.of("../../examples/grievance.app.json"), StandardCharsets.UTF_8));
            // An entity without a workflow, so PUT and PATCH are allowed on it.
            ((com.fasterxml.jackson.databind.node.ArrayNode) root.get("entities")).add(m.readTree(
                    "{\"code\":\"NOTE\",\"label\":\"Note\",\"referencePrefix\":\"NOT\",\"fields\":["
                    + "{\"code\":\"title\",\"label\":\"Title\",\"type\":\"TEXT\",\"required\":true,\"maxLength\":10},"
                    + "{\"code\":\"body\",\"label\":\"Body\",\"type\":\"TEXT\"}]}"));
            PublishedApp app = new PublishedApp(1, new DefinitionParser().parse(root));
            return new PublishedDefinitions() {
                @Override public Optional<PublishedApp> latestPublished(String a) { return Optional.of(app); }
                @Override public Optional<PublishedApp> publishedVersion(String a, int v) {
                    return v == 1 ? Optional.of(app) : Optional.empty();
                }
            };
        }
        @Bean RecordService recordService(PublishedDefinitions defs, InMemoryRecordRepository records,
                                          InMemoryInstanceRepository instances) {
            Clock clock = Clock.systemUTC();
            return new RecordService(defs, records, instances, new InMemoryReferenceCounter(),
                    new AuditService(new InMemoryAuditRepository(), clock), clock);
        }
        @Bean RecordItemAssembler assembler(InMemoryInstanceRepository instances, InMemoryUserRepository users,
                                            PublishedDefinitions defs) {
            return new RecordItemAssembler(instances, users, defs);
        }
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JwtService jwt;
    @Autowired RecordService records;
    @Autowired InMemoryInstanceRepository instances;
    MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();
    final Principal viewer = new Principal(VIEWER, Set.of("SUPERVISOR"));

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    private String auth() {
        return "Bearer " + jwt.issue(new UserAccount(VIEWER, "v", "x", "v", true, Set.of("SUPERVISOR")));
    }

    private static Map<String, Object> payload(String subject) {
        return Map.of("subject", subject, "details", "Deep", "category", "ROAD", "contactEmail", "a@b.co");
    }

    private UUID record(String subject) {
        return records.create("GRIEVANCE", "GRIEVANCE", payload(subject), viewer).id();
    }

    private void instance(UUID recordId, String step, String status, UUID assignee, Instant due, boolean ended,
                          int version) {
        instances.insert(new InstanceState(UUID.randomUUID(), recordId, "GRIEVANCE", version, "GRIEVANCE_FLOW", step,
                status, assignee, due, ended, Instant.now(), 0, VIEWER, Instant.now()));
    }

    private JsonNode read(UUID id) throws Exception {
        return json.readTree(mvc.perform(get(BASE + "/" + id).header("Authorization", auth()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode inList(UUID id) throws Exception {
        String body = mvc.perform(get(BASE + "?size=100").header("Authorization", auth()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode item : json.readTree(body).get("items")) {
            if (item.get("id").asText().equals(id.toString())) {
                return item;
            }
        }
        throw new AssertionError("record " + id + " not in list");
    }

    @Test
    void runningInstanceWithKnownAssignee() throws Exception {
        UUID rid = record("running");
        Instant due = Instant.parse("2030-01-02T03:04:05.123Z");
        instance(rid, "TRIAGE", "Received", KNOWN, due, false, 1);
        for (JsonNode n : new JsonNode[] {read(rid), inList(rid)}) {
            assertThat(n.get("instanceId").isTextual()).isTrue();
            assertThat(n.get("step").asText()).isEqualTo("TRIAGE");
            assertThat(n.get("stepLabel").asText()).isEqualTo("Triage");
            assertThat(n.get("status").asText()).isEqualTo("Received");
            assertThat(n.get("statusColor").asText()).isEqualTo("#64748b");
            assertThat(n.get("assignee").asText()).isEqualTo(KNOWN.toString());
            assertThat(n.get("assigneeName").asText()).isEqualTo("Known Person");
            assertThat(n.get("dueAt").asText()).isEqualTo("2030-01-02T03:04:05.123Z");
            assertThat(n.get("ended").asBoolean()).isFalse();
            assertThat(n.get("referenceNo").asText()).startsWith("GRV-");
            assertThat(n.get("data").get("subject").asText()).isEqualTo("running");
        }
    }

    @Test
    void endedInstanceHasNoAssigneeAndNoDueDate() throws Exception {
        UUID rid = record("ended");
        instance(rid, "END_RESOLVED", "Resolved", KNOWN, Instant.now(), true, 1);
        for (JsonNode n : new JsonNode[] {read(rid), inList(rid)}) {
            assertThat(n.get("ended").asBoolean()).isTrue();
            assertThat(n.get("status").asText()).isEqualTo("Resolved");
            assertThat(n.get("statusColor").asText()).isEqualTo("#22c55e");
            assertThat(n.get("stepLabel").asText()).isEqualTo("Resolved");
            assertThat(n.get("assignee").isNull()).isTrue();
            assertThat(n.get("assigneeName").isNull()).isTrue();
            assertThat(n.get("dueAt").isNull()).isTrue();
        }
    }

    @Test
    void unassignedInstanceHasNullAssigneeAndName() throws Exception {
        UUID rid = record("queue");
        instance(rid, "TRIAGE", "Received", null, null, false, 1);
        for (JsonNode n : new JsonNode[] {read(rid), inList(rid)}) {
            assertThat(n.get("assignee").isNull()).isTrue();
            assertThat(n.get("assigneeName").isNull()).isTrue();
            assertThat(n.get("ended").asBoolean()).isFalse();
        }
    }

    @Test
    void unknownAssigneeKeepsTheIdAndHasNoName() throws Exception {
        UUID rid = record("unknown");
        UUID ghost = UUID.randomUUID();
        instance(rid, "TRIAGE", "Received", ghost, null, false, 1);
        for (JsonNode n : new JsonNode[] {read(rid), inList(rid)}) {
            assertThat(n.get("assignee").asText()).isEqualTo(ghost.toString());
            assertThat(n.get("assigneeName").isNull()).isTrue();
        }
    }

    @Test
    void recordWithoutInstanceHasNullSummaryAndEndedFalse() throws Exception {
        UUID rid = record("plain");
        for (JsonNode n : new JsonNode[] {read(rid), inList(rid)}) {
            for (String k : SUMMARY_KEYS) {
                assertThat(n.has(k)).as(k).isTrue();
                if (!k.equals("ended")) {
                    assertThat(n.get(k).isNull()).as(k).isTrue();
                }
            }
            assertThat(n.get("ended").asBoolean()).isFalse();
        }
    }

    @Test
    void missingPinnedVersionFallsBackToStepCodeWithoutFailingThePage() throws Exception {
        UUID rid = record("orphan");
        instance(rid, "TRIAGE", "Received", null, null, false, 7);
        JsonNode n = inList(rid);
        assertThat(n.get("stepLabel").asText()).isEqualTo("TRIAGE");
        assertThat(n.get("statusColor").isNull()).isTrue();
    }

    @Test
    void createReplaceAndPatchReturnTheSummary() throws Exception {
        String note = "/api/apps/GRIEVANCE/entities/NOTE/records";
        String created = mvc.perform(post(note).header("Authorization", auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"made\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode c = json.readTree(created);
        String id = c.get("id").asText();
        JsonNode replaced = json.readTree(mvc.perform(put(note + "/" + id).header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"again\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode patched = json.readTree(mvc.perform(patch(note + "/" + id).header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"text\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode n : new JsonNode[] {c, replaced, patched}) {
            for (String k : SUMMARY_KEYS) {
                assertThat(n.has(k)).as(k).isTrue();
            }
            assertThat(n.get("ended").asBoolean()).isFalse();
            assertThat(n.get("instanceId").isNull()).isTrue();
        }
        assertThat(patched.get("data").get("body").asText()).isEqualTo("text");
        assertThat(patched.get("data").get("title").asText()).isEqualTo("again");
    }
    @Test
    void existingPropertiesKeepTheirNamesAndComeFirst() throws Exception {
        UUID rid = record("order");
        JsonNode n = read(rid);
        java.util.List<String> names = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly("id", "referenceNo", "appCode", "defVersion", "entityCode", "data",
                "createdBy", "createdAt", "updatedAt", "instanceId", "step", "stepLabel", "status", "statusColor",
                "assignee", "assigneeName", "dueAt", "ended");
    }
}
