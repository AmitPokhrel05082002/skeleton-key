package dev.skeletonkey.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.definition.DefinitionService;
import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.domain.model.DefinitionCache;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.validator.HandlerCatalog;
import dev.skeletonkey.persistence.DefinitionVersionRow;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryDefinitionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

class StartupLoaderTest {
    private static final ObjectMapper OM = new ObjectMapper();

    @TempDir Path dir;
    private final List<String> insertOrder = new ArrayList<>();
    private InMemoryDefinitionRepository repo;
    private String grievance;

    @BeforeEach
    void setUp() throws Exception {
        grievance = Files.readString(Path.of("../../examples/grievance.app.json"), StandardCharsets.UTF_8);
        repo = new InMemoryDefinitionRepository() {
            @Override
            public synchronized void insert(DefinitionVersionRow row) {
                super.insert(row);
                insertOrder.add(row.appCode());
            }
        };
    }

    private String withAppCode(String code) throws Exception {
        ObjectNode root = (ObjectNode) OM.readTree(grievance);
        ((ObjectNode) root.get("app")).put("code", code);
        return OM.writeValueAsString(root);
    }

    private String withUnknownHandler(String code) throws Exception {
        ObjectNode root = (ObjectNode) OM.readTree(withAppCode(code));
        for (JsonNode step : root.get("workflows").get(0).get("steps")) {
            if (step.get("actions") instanceof ArrayNode arr && !arr.isEmpty()) {
                ((ObjectNode) arr.get(0)).put("handler", "noSuchHandler");
                break;
            }
        }
        return OM.writeValueAsString(root);
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private void run(boolean autoPublish) {
        DefinitionParser parser = new DefinitionParser();
        DefinitionLoader loader = new DefinitionLoader();
        DefinitionService service = new DefinitionService(loader, repo, parser, new DefinitionCache(parser),
                HandlerCatalog.empty(), new AuditService(new InMemoryAuditRepository(), Clock.systemUTC()),
                Clock.systemUTC());
        SkeletonProperties props = new SkeletonProperties(new SkeletonProperties.Jwt("x".repeat(32), 3600),
                new SkeletonProperties.Definitions(dir.toString(), autoPublish), null, null);
        new StartupLoader(props, loader, service, repo).run(new DefaultApplicationArguments());
    }

    private String status(String app) {
        return repo.findByAppAndVersion(app, 1).map(DefinitionVersionRow::status).orElse("MISSING");
    }

    @Test
    void loadsFilesInSortedOrder() throws Exception {
        write("b.app.json", withAppCode("BBB"));
        write("c.app.json", withAppCode("CCC"));
        write("a.app.json", withAppCode("AAA"));
        write("notes.txt", "ignored");

        run(false);

        assertThat(insertOrder).containsExactly("AAA", "BBB", "CCC");
    }

    @Test
    void skipsFilesWhoseContentHashIsAlreadyStoredAcrossRestarts() throws Exception {
        write("a.app.json", grievance);

        run(true);
        run(true);
        run(true);

        assertThat(insertOrder).hasSize(1);
        assertThat(repo.maxVersion("GRIEVANCE")).isEqualTo(1);
        assertThat(status("GRIEVANCE")).isEqualTo("PUBLISHED");
    }

    @Test
    void malformedAndSchemaInvalidFilesAreSkippedAndLoadingContinues() throws Exception {
        write("a.json", "{\n  \"app\": ");
        write("b.json", "{}");
        write("c.json", grievance);

        run(true);

        assertThat(insertOrder).containsExactly("GRIEVANCE");
        assertThat(status("GRIEVANCE")).isEqualTo("PUBLISHED");
    }

    @Test
    void autoPublishOnPublishesNewDrafts() throws Exception {
        write("a.json", withAppCode("AAA"));
        write("b.json", withAppCode("BBB"));

        run(true);

        assertThat(status("AAA")).isEqualTo("PUBLISHED");
        assertThat(status("BBB")).isEqualTo("PUBLISHED");
    }

    @Test
    void autoPublishOffLeavesEverythingDraft() throws Exception {
        write("a.json", withAppCode("AAA"));

        run(false);

        assertThat(status("AAA")).isEqualTo("DRAFT");
    }

    @Test
    void draftWithValidatorViolationsStaysDraftAndOthersStillPublish() throws Exception {
        write("a.json", withUnknownHandler("BAD"));
        write("b.json", withAppCode("GOOD"));

        run(true);

        assertThat(status("BAD")).isEqualTo("DRAFT");
        assertThat(status("GOOD")).isEqualTo("PUBLISHED");
    }

    @Test
    void autoPublishDoesNotPublishHashSkippedDrafts() throws Exception {
        write("a.json", grievance);
        run(false);
        assertThat(status("GRIEVANCE")).isEqualTo("DRAFT");

        run(true); // same hash: skipped, so the old DRAFT is not published

        assertThat(status("GRIEVANCE")).isEqualTo("DRAFT");
        assertThat(insertOrder).hasSize(1);
    }
}
