package dev.skeletonkey.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import dev.skeletonkey.audit.AuditService;
import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.domain.model.DefinitionCache;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.persistence.memory.InMemoryAuditRepository;
import dev.skeletonkey.persistence.memory.InMemoryDefinitionRepository;
import dev.skeletonkey.testing.DefinitionArbitraries;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Feature: frontend-backend-support, Property 7: Branding is projected as declared in both projections. */
class BrandingProjectionPropertyTest {

    @Provide
    net.jqwik.api.Arbitrary<JsonNode> docs() {
        return DefinitionArbitraries.validDefinitionJson();
    }

    @Property(tries = 100)
    void brandingIsProjectedAsDeclared(@ForAll("docs") JsonNode doc) {
        InMemoryDefinitionRepository repo = new InMemoryDefinitionRepository();
        DefinitionParser parser = new DefinitionParser();
        DefinitionService service = new DefinitionService(new DefinitionLoader(), repo, parser,
                new DefinitionCache(parser), DefinitionArbitraries.handlerCatalog(),
                new AuditService(new InMemoryAuditRepository(), Clock.systemUTC()), Clock.systemUTC());
        var ref = service.submit(doc.toString());
        service.publish(ref.id(), DefinitionService.SYSTEM_ACTOR);
        DefinitionReadService read = new DefinitionReadService(service, repo);

        String code = doc.get("app").get("code").asText();
        JsonNode declared = doc.get("app").get("branding");
        Principal caller = new Principal(UUID.randomUUID(), Set.of("ROLE_1"));
        for (JsonNode projection : new JsonNode[] {read.readRestricted(code, null, caller),
                read.readPublicSubset(code)}) {
            JsonNode app = projection.get("app");
            if (declared == null) {
                assertFalse(app.has("branding"));
            } else {
                assertEquals(declared, app.get("branding"));
            }
            assertEquals(doc.get("app").get("name"), app.get("name"));
            assertEquals(doc.get("app").get("code"), app.get("code"));
        }
    }
}
