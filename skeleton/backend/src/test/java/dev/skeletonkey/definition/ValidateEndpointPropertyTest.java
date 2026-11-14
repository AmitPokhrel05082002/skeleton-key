package dev.skeletonkey.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.domain.load.DefinitionSchemaException;
import dev.skeletonkey.domain.load.DefinitionSyntaxException;
import dev.skeletonkey.domain.model.DefinitionCache;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.persistence.AuditEntry;
import dev.skeletonkey.persistence.DefinitionVersionRow;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryDefinitionRepository;
import dev.skeletonkey.testing.DefinitionArbitraries;
import dev.skeletonkey.testing.Mutations;
import dev.skeletonkey.web.ApiViolation;
import dev.skeletonkey.web.BadRequest;
import dev.skeletonkey.web.ValidationFailed;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Feature: frontend-backend-support, Properties 11, 12 and 13 (validate endpoint service). */
class ValidateEndpointPropertyTest {

    /** A service over its own in-memory repositories; counts every write. */
    private static final class Harness {
        final AtomicInteger writes = new AtomicInteger();
        final InMemoryDefinitionRepository repo = new InMemoryDefinitionRepository() {
            @Override public synchronized void insert(DefinitionVersionRow row) {
                writes.incrementAndGet();
                super.insert(row);
            }
            @Override public synchronized boolean markPublished(java.util.UUID id, java.time.Instant at) {
                writes.incrementAndGet();
                return super.markPublished(id, at);
            }
        };
        final InMemoryAuditRepository audit = new InMemoryAuditRepository() {
            @Override public synchronized void append(AuditEntry entry) {
                writes.incrementAndGet();
                super.append(entry);
            }
        };
        final DefinitionService service;

        Harness() {
            DefinitionParser parser = new DefinitionParser();
            service = new DefinitionService(new DefinitionLoader(), repo, parser, new DefinitionCache(parser),
                    DefinitionArbitraries.handlerCatalog(), new AuditService(audit, Clock.systemUTC()),
                    Clock.systemUTC());
        }
    }

    /** Valid documents, documents with one injected defect, and documents the schema rejects. */
    @Provide
    Arbitrary<String> texts() {
        Arbitrary<String> valid = DefinitionArbitraries.validDefinition().map(d -> d.jsonText());
        Arbitrary<String> defects = Mutations.oneOfTenDefects().map(m -> m.jsonText());
        Arbitrary<String> schemaBroken = Combinators.combine(DefinitionArbitraries.validDefinition(),
                        Arbitraries.of("roles", "entities", "workflows", "app", "schemaVersion"))
                .as((d, key) -> {
                    ObjectNode copy = d.json().deepCopy();
                    copy.remove(key);
                    return copy.toString();
                });
        Arbitrary<String> badWidget = DefinitionArbitraries.validDefinition().map(d -> {
            ObjectNode copy = d.json().deepCopy();
            ((ObjectNode) copy.get("entities").get(0).get("fields").get(0)).put("widget", "9 not valid");
            return copy.toString();
        });
        return Arbitraries.oneOf(valid, defects, schemaBroken, badWidget);
    }

    @Provide
    Arbitrary<String> validTexts() {
        return DefinitionArbitraries.validDefinition().map(d -> d.jsonText());
    }

    private static List<ApiViolation> expectedByPublishing(String text) {
        Harness h = new Harness();
        DefinitionVersionRef ref;
        try {
            ref = h.service.submit(text);
        } catch (DefinitionSchemaException e) {
            return e.violations().stream().map(v -> new ApiViolation(v.path(), v.rule(), v.message())).toList();
        }
        try {
            h.service.publish(ref.id(), DefinitionService.SYSTEM_ACTOR);
            return List.of();
        } catch (ValidationFailed e) {
            return e.violations();
        }
    }

    // Feature: frontend-backend-support, Property 11: Validate equals Publish
    @Property(tries = 200)
    void validateEqualsPublish(@ForAll("texts") String text) {
        assertEquals(expectedByPublishing(text), new Harness().service.validateText(text));
    }

    // Feature: frontend-backend-support, Property 12: Validate is idempotent and has no side effect
    @Property(tries = 100)
    void validateIsIdempotentAndWritesNothing(@ForAll("texts") String text) {
        Harness h = new Harness();
        List<ApiViolation> first = h.service.validateText(text);
        List<ApiViolation> second = h.service.validateText(text);
        assertEquals(first, second);
        assertEquals(0, h.writes.get(), "validate must not write to the repository or the audit log");
        assertTrue(h.repo.findAppCodes().isEmpty());
    }

    // Feature: frontend-backend-support, Property 13: Unparseable text is a 400 with a position
    @Property(tries = 100)
    void truncatedTextIsASyntaxErrorWithPosition(@ForAll("validTexts") String text,
                                                 @ForAll("cuts") double fraction) {
        int cut = (int) Math.floor(fraction * text.length());
        String truncated = text.substring(0, Math.min(cut, text.length() - 1));
        Harness h = new Harness();
        DefinitionSyntaxException e = assertThrows(DefinitionSyntaxException.class,
                () -> h.service.validateText(truncated));
        assertTrue(e.line() >= 1 && e.column() >= 1);
        assertEquals(0, h.writes.get());
    }

    @Provide
    Arbitrary<Double> cuts() {
        return Arbitraries.doubles().between(0.0, 1.0);
    }

    // Feature: frontend-backend-support, Property 13 (non-object documents)
    @Property(tries = 20)
    void nonObjectDocumentIsABadRequest(@ForAll("nonObjects") String text) {
        assertThrows(BadRequest.class, () -> new Harness().service.validateText(text));
    }

    @Provide
    Arbitrary<String> nonObjects() {
        return Arbitraries.of("[]", "[1,2]", "42", "\"text\"", "true", "null");
    }

    @Property(tries = 1)
    void emptyTextIsASyntaxErrorAtOneOne() {
        DefinitionSyntaxException e = assertThrows(DefinitionSyntaxException.class,
                () -> new Harness().service.validateText(""));
        assertEquals(1, e.line());
        assertEquals(1, e.column());
    }
}
