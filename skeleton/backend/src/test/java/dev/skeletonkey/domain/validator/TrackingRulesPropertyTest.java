package dev.skeletonkey.domain.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.domain.load.DefinitionLoader;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.testing.DefinitionArbitraries;
import dev.skeletonkey.testing.DefinitionArbitraries.GeneratedDefinition;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 10 (the Validator reports a tracking defect iff it exists) and Property 11 (tracking survives parsing and
 * stays within the schema).
 */
class TrackingRulesPropertyTest {

    private static final DefinitionLoader LOADER = new DefinitionLoader();
    private static final DefinitionParser PARSER = new DefinitionParser();
    private static final Validator VALIDATOR = new Validator(DefinitionArbitraries.handlerCatalog());
    private static final List<String> TRACKING_RULES = List.of(TrackingRules.BAD_TRACKING_FIELD,
            TrackingRules.TRACKING_FIELD_NOT_EMAIL, TrackingRules.TRACKING_NEEDS_REFERENCE);

    enum Variant { VALID, MISSING_FIELD, NOT_EMAIL, NO_PREFIX }

    @Provide
    Arbitrary<GeneratedDefinition> definitions() {
        return DefinitionArbitraries.validDefinition(6, 4, 10);
    }

    @Property(tries = 200)
    void reportsExactlyTheInjectedTrackingDefect(@ForAll("definitions") GeneratedDefinition g,
                                                 @ForAll Variant variant) throws Exception {
        assertThat(VALIDATOR.validate(g.model()).violations()).isEmpty();
        ObjectNode json = g.json().deepCopy();
        ObjectNode entity = (ObjectNode) json.get("entities").get(0);
        entity.put("referencePrefix", "TRK");
        String contact = switch (variant) {
            case MISSING_FIELD -> "noSuchField";
            case NOT_EMAIL -> "fText";
            default -> "fEmail";
        };
        entity.putObject("tracking").put("contactField", contact);
        if (variant == Variant.NO_PREFIX) {
            entity.remove("referencePrefix");
        }
        assertThat(LOADER.checkSchema(json)).isEmpty();
        AppDefinition model = PARSER.parse(json);
        List<String> rules = VALIDATOR.validate(model).violations().stream().map(Violation::rule)
                .filter(TRACKING_RULES::contains).toList();
        switch (variant) {
            case VALID -> assertThat(rules).isEmpty();
            case MISSING_FIELD -> assertThat(rules).containsExactly(TrackingRules.BAD_TRACKING_FIELD);
            case NOT_EMAIL -> assertThat(rules).containsExactly(TrackingRules.TRACKING_FIELD_NOT_EMAIL);
            case NO_PREFIX -> assertThat(rules).containsExactly(TrackingRules.TRACKING_NEEDS_REFERENCE);
        }
    }

    @Property(tries = 200)
    void trackingSurvivesParsing(@ForAll("definitions") GeneratedDefinition g) {
        JsonNode entities = g.json().get("entities");
        for (int i = 0; i < entities.size(); i++) {
            JsonNode e = entities.get(i);
            Entity parsed = g.model().entities().get(i);
            if (e.has("tracking")) {
                assertThat(parsed.tracking()).isEqualTo(new Entity.Tracking(e.get("tracking").get("contactField").asText()));
            } else {
                assertThat(parsed.tracking()).isNull();
            }
        }
    }

    @Property(tries = 50)
    void schemaRejectsMalformedTracking(@ForAll("definitions") GeneratedDefinition g,
                                        @ForAll("badTracking") String bad) throws Exception {
        ObjectNode json = g.json().deepCopy();
        ((ObjectNode) json.get("entities").get(0)).set("tracking",
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(bad));
        assertThat(LOADER.checkSchema(json)).isNotEmpty();
    }

    @Provide
    Arbitrary<String> badTracking() {
        return Arbitraries.of("{}", "{\"contactField\":\"Bad-Name\"}", "{\"contactField\":\"fEmail\",\"x\":1}",
                "true", "\"fEmail\"", "{\"contactField\":3}");
    }
}
