package dev.skeletonkey.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.domain.load.LoadedDefinition;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.DefinitionCache;
import dev.skeletonkey.domain.model.DefinitionParseException;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.validator.HandlerCatalog;
import dev.skeletonkey.domain.validator.ValidationReport;
import dev.skeletonkey.domain.validator.Validator;
import dev.skeletonkey.persistence.DefinitionRepository;
import dev.skeletonkey.persistence.DefinitionVersionRow;
import dev.skeletonkey.persistence.DuplicateVersionException;
import dev.skeletonkey.record.PublishedDefinitions;
import dev.skeletonkey.web.ApiViolation;
import dev.skeletonkey.web.Conflict;
import dev.skeletonkey.web.NotFound;
import dev.skeletonkey.web.ValidationFailed;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores, edits, reads and publishes whole-document Definition_Versions (Req 1, 2, 5). Publishing runs the full
 * {@link Validator}, including expression and handler rules against the real {@link HandlerCatalog}.
 */
@Service
public class DefinitionService implements PublishedDefinitions {
    /** Reserved actor for Auto_Publish (Req 12.10). */
    public static final Principal SYSTEM_ACTOR =
            new Principal(new UUID(0L, 0L), Set.of());
    public static final String AUDIT_TARGET = "DEFINITION_VERSION";
    private static final int INSERT_ATTEMPTS = 2;

    private final DefinitionLoader loader;
    private final DefinitionRepository repository;
    private final DefinitionParser parser;
    private final DefinitionCache cache;
    private final Validator validator;
    private final AuditService audit;
    private final Clock clock;

    @Autowired
    public DefinitionService(DefinitionLoader loader, DefinitionRepository repository, DefinitionParser parser,
                             DefinitionCache cache, HandlerCatalog handlerCatalog, AuditService audit,
                             ObjectProvider<Clock> clock) {
        this(loader, repository, parser, cache, handlerCatalog, audit, clock.getIfAvailable(Clock::systemUTC));
    }

    public DefinitionService(DefinitionLoader loader, DefinitionRepository repository, DefinitionParser parser,
                             DefinitionCache cache, HandlerCatalog handlerCatalog, AuditService audit, Clock clock) {
        this.loader = loader;
        this.repository = repository;
        this.parser = parser;
        this.cache = cache;
        // The full validator: structural, workflow graph, expression and handler rules (Req 4).
        this.validator = new Validator(handlerCatalog);
        this.audit = audit;
        this.clock = clock;
    }

    /** Stores a schema-valid document as a new DRAFT. 400 on malformed JSON, 422 on schema violations. */
    public DefinitionVersionRef submit(String json) {
        LoadedDefinition loaded = loader.load(json);
        return storeDraft(appCodeOf(loaded.document()), loaded);
    }

    /**
     * Stores an edit of {@code baseVersion} as a new DRAFT of the whole document; the base is left unchanged
     * (Req 5.2). 404 when the app or base version does not exist (Req 5.8).
     */
    public DefinitionVersionRef edit(String appCode, int baseVersion, String json) {
        repository.findByAppAndVersion(appCode, baseVersion)
                .orElseThrow(() -> new NotFound("Definition " + appCode + " version " + baseVersion + " not found"));
        LoadedDefinition loaded = loader.load(json);
        String declared = appCodeOf(loaded.document());
        if (!appCode.equals(declared)) {
            throw new ValidationFailed("Document app code '" + declared + "' differs from '" + appCode + "'",
                    List.of(new ApiViolation("/app/code", "APP_CODE_MISMATCH",
                            "Expected app code '" + appCode + "'")));
        }
        return storeDraft(appCode, loaded);
    }

    /**
     * Update of an existing version by id. A PUBLISHED version is never changed in place: 409 (Req 5.3).
     * A DRAFT is not changed in place either; the edit is stored as a new DRAFT.
     */
    public DefinitionVersionRef update(UUID id, String json) {
        DefinitionVersionRow base = find(id);
        if (!base.isDraft()) {
            throw new Conflict("DEFINITION_PUBLISHED",
                    "Version " + base.version() + " of " + base.appCode() + " is PUBLISHED and cannot be updated");
        }
        return edit(base.appCode(), base.version(), json);
    }

    /** The stored document, JSON-equal to what was submitted. 404 when the id is unknown (Req 1.8). */
    public JsonNode read(UUID id) {
        return find(id).document();
    }

    public DefinitionVersionRef describe(UUID id) {
        return DefinitionVersionRef.of(find(id));
    }

    /**
     * Validates and publishes a DRAFT. 404 unknown id, 409 when not DRAFT, 422 with every violation otherwise
     * (the version stays DRAFT). Writes the publish audit entry in the same transaction (Req 12.10, 12.11).
     */
    @Transactional
    public DefinitionVersionRef publish(UUID id, Principal actor) {
        DefinitionVersionRow row = find(id);
        if (!row.isDraft()) {
            throw new Conflict("INVALID_STATUS",
                    "Only a DRAFT can be published; version " + row.version() + " of " + row.appCode()
                            + " is " + row.status());
        }
        ValidationReport report = validate(row.document());
        if (!report.ok()) {
            throw new ValidationFailed("Definition has " + report.violations().size() + " violation(s)",
                    report.violations().stream()
                            .map(v -> new ApiViolation(v.path(), v.rule(), v.message())).toList());
        }
        if (!repository.markPublished(id, Instant.now(clock))) {
            throw new Conflict("INVALID_STATUS", "Version was published or removed concurrently");
        }
        ObjectNode details = JsonNodeFactory.instance.objectNode();
        details.put("appCode", row.appCode());
        details.put("version", row.version());
        audit.record(actor == null ? null : actor.userId(), AuditService.PUBLISH, AUDIT_TARGET, id.toString(), details);
        return DefinitionVersionRef.of(find(id));
    }

    /** The highest PUBLISHED version of the app, parsed and cached by (app code, version). */
    @Override
    public Optional<PublishedApp> latestPublished(String appCode) {
        return repository.findLatestPublished(appCode).map(r -> new PublishedApp(r.version(),
                cache.get(r.appCode(), r.version(), r::document)));
    }

    private ValidationReport validate(JsonNode document) {
        AppDefinition def;
        try {
            def = parser.parse(document);
        } catch (DefinitionParseException e) {
            throw new ValidationFailed("Definition cannot be parsed",
                    List.of(new ApiViolation("", "PARSE", e.getMessage())));
        }
        return validator.validate(def);
    }

    /** version = max + 1, guarded by the unique (app code, version) constraint with one retry (Req 1.4). */
    private DefinitionVersionRef storeDraft(String appCode, LoadedDefinition loaded) {
        for (int attempt = 1; ; attempt++) {
            DefinitionVersionRow row = new DefinitionVersionRow(UUID.randomUUID(), appCode,
                    repository.maxVersion(appCode) + 1, DefinitionVersionRow.DRAFT, loaded.contentHash(),
                    loaded.document(), Instant.now(clock), null);
            try {
                repository.insert(row);
                return DefinitionVersionRef.of(row);
            } catch (DuplicateVersionException e) {
                if (attempt >= INSERT_ATTEMPTS) {
                    throw new Conflict("VERSION_CONFLICT", "Could not allocate a version number; retry");
                }
            }
        }
    }

    private DefinitionVersionRow find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFound("Definition version " + id + " not found"));
    }

    private static String appCodeOf(JsonNode document) {
        return document.path("app").path("code").asText();
    }
}
