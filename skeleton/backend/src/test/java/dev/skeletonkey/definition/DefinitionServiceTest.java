package dev.skeletonkey.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.domain.load.DefinitionSchemaException;
import dev.skeletonkey.domain.load.DefinitionSyntaxException;
import dev.skeletonkey.domain.model.DefinitionCache;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.validator.HandlerCatalog;
import dev.skeletonkey.persistence.AuditEntry;
import dev.skeletonkey.persistence.DefinitionVersionRow;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryDefinitionRepository;
import dev.skeletonkey.web.Conflict;
import dev.skeletonkey.web.NotFound;
import dev.skeletonkey.web.ValidationFailed;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefinitionServiceTest {
    private static final ObjectMapper OM = new ObjectMapper();
    private static final Principal ACTOR = new Principal(UUID.randomUUID(), Set.of("ADMIN"));

    private InMemoryDefinitionRepository repo;
    private InMemoryAuditRepository auditRepo;
    private DefinitionService service;
    private String grievance;

    @BeforeEach
    void setUp() throws Exception {
        grievance = Files.readString(Path.of("../../examples/grievance.app.json"), StandardCharsets.UTF_8);
        repo = new InMemoryDefinitionRepository();
        auditRepo = new InMemoryAuditRepository();
        service = serviceWith(HandlerCatalog.empty());
    }

    private DefinitionService serviceWith(HandlerCatalog catalog) {
        DefinitionParser parser = new DefinitionParser();
        return new DefinitionService(new DefinitionLoader(), repo, parser, new DefinitionCache(parser), catalog,
                new AuditService(auditRepo, Clock.systemUTC()), Clock.systemUTC());
    }

    /** The grievance example with a mutation applied to its first action (the first step that has actions). */
    private String withFirstAction(java.util.function.Consumer<ObjectNode> mutate) throws Exception {
        ObjectNode root = (ObjectNode) OM.readTree(grievance);
        for (JsonNode wf : root.get("workflows")) {
            for (JsonNode step : wf.get("steps")) {
                JsonNode actions = step.get("actions");
                if (actions instanceof ArrayNode arr && !arr.isEmpty()) {
                    mutate.accept((ObjectNode) arr.get(0));
                    return OM.writeValueAsString(root);
                }
            }
        }
        throw new IllegalStateException("example has no actions");
    }

    @Test
    void submitAssignsIncreasingVersionsAndReadReturnsDocument() throws Exception {
        DefinitionVersionRef v1 = service.submit(grievance);
        DefinitionVersionRef v2 = service.submit(grievance);

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v1.status()).isEqualTo("DRAFT");
        assertThat(service.read(v1.id())).isEqualTo(OM.readTree(grievance));
    }

    @Test
    void submitRejectsMalformedJsonAndSchemaViolationsWithoutStoring() {
        assertThatThrownBy(() -> service.submit("{\n  \"app\": ")).isInstanceOf(DefinitionSyntaxException.class);
        assertThatThrownBy(() -> service.submit("{}")).isInstanceOf(DefinitionSchemaException.class);
        assertThat(repo.maxVersion("GRIEVANCE")).isZero();
    }

    @Test
    void retriesOnceWhenVersionNumberIsTaken() {
        repo.failNextInsertsWithDuplicate = 1;
        assertThat(service.submit(grievance).version()).isEqualTo(1);

        repo.failNextInsertsWithDuplicate = 2;
        assertThatThrownBy(() -> service.submit(grievance)).isInstanceOf(Conflict.class);
    }

    @Test
    void readOfUnknownIdIsNotFound() {
        assertThatThrownBy(() -> service.read(UUID.randomUUID())).isInstanceOf(NotFound.class);
    }

    @Test
    void publishValidDraftSetsPublishedAndWritesAuditEntry() {
        DefinitionVersionRef draft = service.submit(grievance);

        DefinitionVersionRef published = service.publish(draft.id(), ACTOR);

        assertThat(published.status()).isEqualTo("PUBLISHED");
        List<AuditEntry> entries = auditRepo.findByTarget(DefinitionService.AUDIT_TARGET, draft.id().toString());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).action()).isEqualTo("publish");
        assertThat(entries.get(0).actorId()).isEqualTo(ACTOR.userId());
        assertThat(service.latestPublished(draft.appCode())).get()
                .satisfies(p -> assertThat(p.version()).isEqualTo(1));
    }

    @Test
    void publishOfNonDraftIsConflictAndUnknownIdIsNotFound() {
        DefinitionVersionRef draft = service.submit(grievance);
        service.publish(draft.id(), ACTOR);

        assertThatThrownBy(() -> service.publish(draft.id(), ACTOR)).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> service.publish(UUID.randomUUID(), ACTOR)).isInstanceOf(NotFound.class);
        assertThat(auditRepo.findByTarget(DefinitionService.AUDIT_TARGET, draft.id().toString())).hasSize(1);
    }

    @Test
    void publishRunsExpressionAndHandlerRulesRejectingUnknownHandler() throws Exception {
        DefinitionVersionRef draft = service.submit(withFirstAction(a -> a.put("handler", "noSuchHandler")));

        assertThatThrownBy(() -> service.publish(draft.id(), ACTOR))
                .isInstanceOfSatisfying(ValidationFailed.class, e ->
                        assertThat(e.violations()).extracting("rule").contains("UNKNOWN_HANDLER"));
        assertThat(repo.findById(draft.id())).get().extracting(DefinitionVersionRow::status).isEqualTo("DRAFT");
        assertThat(auditRepo.findByTarget(DefinitionService.AUDIT_TARGET, draft.id().toString())).isEmpty();
    }

    @Test
    void publishAcceptsHandlerKnownToTheRealCatalog() throws Exception {
        DefinitionService withCatalog = serviceWith(name -> name.equals("knownHandler"));
        DefinitionVersionRef draft = withCatalog.submit(withFirstAction(a -> a.put("handler", "knownHandler")));

        assertThat(withCatalog.publish(draft.id(), ACTOR).status()).isEqualTo("PUBLISHED");
    }

    @Test
    void publishRejectsBadExpression() throws Exception {
        DefinitionVersionRef draft = service.submit(withFirstAction(a -> {
            ObjectNode bad = OM.createObjectNode();
            bad.set("notAnOperator", OM.createArrayNode().add(1));
            a.set("when", bad);
        }));

        assertThatThrownBy(() -> service.publish(draft.id(), ACTOR))
                .isInstanceOfSatisfying(ValidationFailed.class, e ->
                        assertThat(e.violations()).extracting("rule").contains("EXPR_OPERATOR"));
        assertThat(repo.findById(draft.id())).get().extracting(DefinitionVersionRow::status).isEqualTo("DRAFT");
    }

    @Test
    void editStoresNewDraftAndLeavesPublishedUnchanged() {
        DefinitionVersionRef v1 = service.submit(grievance);
        service.publish(v1.id(), ACTOR);

        DefinitionVersionRef v2 = service.edit(v1.appCode(), 1, grievance);

        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo("DRAFT");
        assertThat(service.describe(v1.id()).status()).isEqualTo("PUBLISHED");
        assertThatThrownBy(() -> service.edit(v1.appCode(), 9, grievance)).isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> service.edit("OTHER", 1, grievance)).isInstanceOf(NotFound.class);
    }

    @Test
    void updatingPublishedVersionInPlaceIsConflict() {
        DefinitionVersionRef v1 = service.submit(grievance);
        service.publish(v1.id(), ACTOR);

        assertThatThrownBy(() -> service.update(v1.id(), grievance)).isInstanceOf(Conflict.class);
        assertThat(repo.maxVersion(v1.appCode())).isEqualTo(1);
    }

    @Test
    void latestPublishedIgnoresDraftsAndIsEmptyWithoutPublishedVersion() {
        DefinitionVersionRef v1 = service.submit(grievance);
        assertThat(service.latestPublished(v1.appCode())).isEmpty();

        service.publish(v1.id(), ACTOR);
        service.submit(grievance);
        assertThat(service.latestPublished(v1.appCode())).get().satisfies(p -> assertThat(p.version()).isEqualTo(1));
    }
}
