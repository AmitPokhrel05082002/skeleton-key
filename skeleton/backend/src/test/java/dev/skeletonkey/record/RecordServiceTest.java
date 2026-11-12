package dev.skeletonkey.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryInstanceRepository;
import dev.skeletonkey.persistence.memory.InMemoryRecordRepository;
import dev.skeletonkey.persistence.memory.InMemoryReferenceCounter;
import dev.skeletonkey.web.Conflict;
import dev.skeletonkey.web.NotFound;
import dev.skeletonkey.web.ValidationFailed;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecordServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);

    private final Principal user = new Principal(UUID.randomUUID(), Set.of("STAFF"));
    private InMemoryRecordRepository records;
    private InMemoryInstanceRepository instances;
    private InMemoryAuditRepository auditRepo;
    private PublishedDefinitions defs;
    private boolean published;
    private RecordService service;

    private static AppDefinition definition() throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(Path.of("../../examples/grievance.app.json")));
        ObjectNode note = MAPPER.createObjectNode();
        note.put("code", "NOTE").put("label", "Note").put("referencePrefix", "NOT");
        ArrayNode fields = note.putArray("fields");
        fields.addObject().put("code", "title").put("label", "Title").put("type", "TEXT")
                .put("required", true).put("maxLength", 10);
        fields.addObject().put("code", "body").put("label", "Body").put("type", "TEXT");
        ((ArrayNode) root.get("entities")).add(note);
        return new DefinitionParser().parse(root);
    }

    @BeforeEach
    void setUp() throws Exception {
        AppDefinition def = definition();
        published = true;
        defs = app -> published && app.equals("GRIEVANCE")
                ? Optional.of(new PublishedDefinitions.PublishedApp(3, def)) : Optional.empty();
        records = new InMemoryRecordRepository();
        instances = new InMemoryInstanceRepository();
        auditRepo = new InMemoryAuditRepository();
        service = new RecordService(defs, records, instances, new InMemoryReferenceCounter(),
                new AuditService(auditRepo, CLOCK), CLOCK);
    }

    private Map<String, Object> note(String title) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        return m;
    }

    @Test
    void createAssignsSequentialReferencesAndAudits() {
        RecordView a = service.create("GRIEVANCE", "NOTE", note("one"), user);
        RecordView b = service.create("GRIEVANCE", "NOTE", note("two"), user);
        assertThat(a.referenceNo()).isEqualTo("NOT-2026-000001");
        assertThat(b.referenceNo()).isEqualTo("NOT-2026-000002");
        assertThat(a.defVersion()).isEqualTo(3);
        assertThat(auditRepo.findByTarget("record", a.id().toString()))
                .singleElement().satisfies(e -> {
                    assertThat(e.action()).isEqualTo("create");
                    assertThat(e.actorId()).isEqualTo(user.userId());
                });
    }

    @Test
    void createRejectsInvalidPayloadAndStoresNothing() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("body", "x");
        bad.put("bogus", 1);
        assertThatThrownBy(() -> service.create("GRIEVANCE", "NOTE", bad, user))
                .isInstanceOf(ValidationFailed.class)
                .satisfies(e -> assertThat(((ValidationFailed) e).violations()).hasSize(2));
    }

    @Test
    void unpublishedAppIsConflictForCreateAndUpdate() {
        RecordView r = service.create("GRIEVANCE", "NOTE", note("one"), user);
        published = false;
        assertThatThrownBy(() -> service.create("GRIEVANCE", "NOTE", note("two"), user))
                .isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> service.update("GRIEVANCE", "NOTE", r.id(), note("new"), true, user))
                .isInstanceOf(Conflict.class);
    }

    @Test
    void readReturnsStoredValuesAndHidesMissingOrInvisible() {
        RecordView r = service.create("GRIEVANCE", "NOTE", note("one"), user);
        assertThat(service.read("GRIEVANCE", "NOTE", r.id(), user).data()).containsEntry("title", "one");
        assertThatThrownBy(() -> service.read("GRIEVANCE", "NOTE", UUID.randomUUID(), user))
                .isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> service.read("GRIEVANCE", "NOTE", r.id(), Principal.anonymous("PUBLIC")))
                .isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> service.read("GRIEVANCE", "GRIEVANCE", r.id(), user))
                .isInstanceOf(NotFound.class);
    }

    @Test
    void putReplacesAndPatchMergesWithAudit() {
        RecordView r = service.create("GRIEVANCE", "NOTE", Map.of("title", "one", "body", "b"), user);
        service.update("GRIEVANCE", "NOTE", r.id(), Map.of("title", "two"), false, user);
        assertThat(service.read("GRIEVANCE", "NOTE", r.id(), user).data())
                .containsEntry("title", "two").containsEntry("body", "b");
        service.update("GRIEVANCE", "NOTE", r.id(), Map.of("title", "three"), true, user);
        assertThat(service.read("GRIEVANCE", "NOTE", r.id(), user).data())
                .containsEntry("title", "three").doesNotContainKey("body");
        assertThat(auditRepo.findByTarget("record", r.id().toString())).extracting(e -> e.action())
                .containsExactly("create", "update", "update");
    }

    @Test
    void invalidUpdateLeavesDataUnchanged() {
        RecordView r = service.create("GRIEVANCE", "NOTE", note("one"), user);
        assertThatThrownBy(() -> service.update("GRIEVANCE", "NOTE", r.id(), note("far too long title"), false, user))
                .isInstanceOf(ValidationFailed.class);
        assertThat(service.read("GRIEVANCE", "NOTE", r.id(), user).data()).containsEntry("title", "one");
    }

    @Test
    void recordWithWorkflowInstanceOrWorkflowEntityIsConflict() {
        RecordView r = service.create("GRIEVANCE", "NOTE", note("one"), user);
        instances.insert(new InstanceState(UUID.randomUUID(), r.id(), "GRIEVANCE", 3, "GRIEVANCE_FLOW", "TRIAGE",
                "Received", null, null, false, Instant.now(CLOCK), 0), user.userId());
        assertThatThrownBy(() -> service.update("GRIEVANCE", "NOTE", r.id(), note("x"), false, user))
                .isInstanceOf(Conflict.class).hasMessageContaining("workflow");

        // GRIEVANCE entity has a workflow in the definition, so its records are never updated directly.
        Map<String, Object> g = Map.of("subject", "s", "details", "d", "category", "ROAD",
                "contactEmail", "a@b.co");
        RecordView gr = service.create("GRIEVANCE", "GRIEVANCE", g, user);
        assertThatThrownBy(() -> service.update("GRIEVANCE", "GRIEVANCE", gr.id(), Map.of("subject", "t"), false, user))
                .isInstanceOf(Conflict.class);
        assertThat(service.read("GRIEVANCE", "GRIEVANCE", gr.id(), user).data()).containsEntry("subject", "s");
    }
}
