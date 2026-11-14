package dev.skeletonkey.domain.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Field;
import dev.skeletonkey.testing.DefinitionArbitraries;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Feature: frontend-backend-support, Property 9: Widget round trip. */
class WidgetRoundTripPropertyTest {
    private final DefinitionLoader loader = new DefinitionLoader();

    @Provide
    net.jqwik.api.Arbitrary<JsonNode> docs() {
        return DefinitionArbitraries.validDefinitionJson();
    }

    @Property(tries = 200)
    void widgetSurvivesLoadSerializeLoad(@ForAll("docs") JsonNode doc) {
        LoadedDefinition first = loader.load(doc.toString());
        LoadedDefinition second = loader.load(first.document().toString());
        assertEquals(first.document(), second.document());
        assertEquals(doc, second.document());
        assertEquals(first.contentHash(), second.contentHash());

        // Declared widgets are returned by the parser; fields without one get none (and none is added).
        AppDefinition def = new DefinitionParser().parse(second.document());
        JsonNode entities = second.document().get("entities");
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = def.entities().get(i);
            JsonNode fields = entities.get(i).get("fields");
            for (int j = 0; j < fields.size(); j++) {
                JsonNode declared = fields.get(j).get("widget");
                Field f = entity.fields().get(j);
                if (declared == null) {
                    assertTrue(f.widget() == null);
                } else {
                    assertEquals(declared.asText(), f.widget());
                }
            }
        }
    }
}
