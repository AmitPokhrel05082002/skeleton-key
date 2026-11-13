package dev.skeletonkey.app;

import dev.skeletonkey.config.SkeletonProperties;
import dev.skeletonkey.definition.DefinitionService;
import dev.skeletonkey.definition.DefinitionVersionRef;
import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.domain.load.DefinitionSchemaException;
import dev.skeletonkey.domain.load.DefinitionSyntaxException;
import dev.skeletonkey.domain.load.LoadedDefinition;
import dev.skeletonkey.persistence.DefinitionRepository;
import dev.skeletonkey.web.ValidationFailed;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Loads every {@code *.json} file of {@code skeletonkey.definitions.dir} as a DRAFT Definition_Version and, when
 * {@code skeletonkey.definitions.auto-publish} is true, publishes the DRAFTs stored by this run (Req 1.5, 1.6, 1.10-1.13,
 * 12.10). Runs after {@link SeedLoader} and, as an {@code ApplicationRunner}, after the handler registry is built.
 * A bad file is logged with its name and never stops the loop or the startup.
 */
@Component
@Order(2)
public class StartupLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupLoader.class);

    private final SkeletonProperties props;
    private final DefinitionLoader loader;
    private final DefinitionService definitions;
    private final DefinitionRepository repository;

    public StartupLoader(SkeletonProperties props, DefinitionLoader loader, DefinitionService definitions,
                         DefinitionRepository repository) {
        this.props = props;
        this.loader = loader;
        this.definitions = definitions;
        this.repository = repository;
    }

    private record Stored(String file, DefinitionVersionRef ref) {}

    @Override
    public void run(ApplicationArguments args) {
        String dir = props.definitions() == null ? null : props.definitions().dir();
        if (dir == null || dir.isBlank()) {
            log.info("No definitions directory configured; skipping definition loading");
            return;
        }
        List<Stored> stored = load(Path.of(dir));
        boolean autoPublish = props.definitions().autoPublish();
        if (!autoPublish) {
            log.info("Auto-publish is off; {} new definition(s) left as DRAFT", stored.size());
            return;
        }
        for (Stored s : stored) {
            publish(s);
        }
    }

    private List<Stored> load(Path dir) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            log.warn("Definitions directory '{}' does not exist or is not a directory", dir);
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"))
                    .sorted().forEach(files::add);
        } catch (IOException e) {
            log.error("Cannot list definitions directory '{}': {}", dir, e.toString());
            return List.of();
        }
        List<Stored> stored = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                LoadedDefinition loaded = loader.load(json);
                if (repository.existsByContentHash(loaded.contentHash())) {
                    log.info("Definition file '{}' already stored (same content hash); skipped", name);
                    continue;
                }
                DefinitionVersionRef ref = definitions.submit(json);
                log.info("Definition file '{}' stored as DRAFT {} version {} ({})", name, ref.appCode(),
                        ref.version(), ref.id());
                stored.add(new Stored(name, ref));
            } catch (DefinitionSyntaxException e) {
                log.error("Definition file '{}' is not valid JSON: {}", name, e.getMessage());
            } catch (DefinitionSchemaException e) {
                log.error("Definition file '{}' violates the schema ({} violation(s))", name, e.violations().size());
                e.violations().forEach(v -> log.error("  {} {}: {}", name, v.path(), v.message()));
            } catch (IOException | RuntimeException e) {
                log.error("Definition file '{}' could not be loaded: {}", name, e.toString());
            }
        }
        return stored;
    }

    private void publish(Stored s) {
        try {
            DefinitionVersionRef pub = definitions.publish(s.ref().id(), DefinitionService.SYSTEM_ACTOR);
            log.info("Definition file '{}' published as {} version {} ({})", s.file(), pub.appCode(),
                    pub.version(), pub.id());
        } catch (ValidationFailed e) {
            log.error("Definition file '{}' ({} version {}, id {}) stays DRAFT: {}", s.file(), s.ref().appCode(),
                    s.ref().version(), s.ref().id(), e.getMessage());
            e.violations().forEach(v -> log.error("  {} {} [{}]: {}", s.file(), v.field(), v.rule(), v.message()));
        } catch (RuntimeException e) {
            log.error("Definition file '{}' ({} version {}, id {}) could not be published: {}", s.file(),
                    s.ref().appCode(), s.ref().version(), s.ref().id(), e.toString());
        }
    }
}
