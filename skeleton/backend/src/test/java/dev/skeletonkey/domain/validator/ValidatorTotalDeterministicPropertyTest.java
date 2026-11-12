package dev.skeletonkey.domain.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.DefinitionParseException;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.testing.DefinitionArbitraries;
import dev.skeletonkey.testing.DefinitionArbitraries.GeneratedDefinition;
import dev.skeletonkey.testing.ExpressionArbitraries;
import dev.skeletonkey.testing.Mutations;
import dev.skeletonkey.testing.Mutations.MutatedDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;

/**
 * Feature: skeleton-backend, Property 6: Validator is total, deterministic and order-insensitive.
 *
 * <p>Order-insensitivity limitation: report paths use element codes (not array indexes) today, but the
 * guarantee asserted here is deliberately weaker and does not depend on that: the multiset of
 * (rule, message) pairs is compared, never paths, after permuting the top-level collections
 * (roles, entities, forms, views, workflows) and the fields of each entity, then re-parsing. Step, action and
 * transition arrays are not permuted because their order is semantically meaningful (the "next step in array
 * order" routing rule, and the first step's role in reachability).
 */
class ValidatorTotalDeterministicPropertyTest {

    private final Validator validator = new Validator(DefinitionArbitraries.handlerCatalog());
    private final DefinitionParser parser = new DefinitionParser();

    @Provide
    Arbitrary<MutatedDefinition> mutated() {
        return Mutations.oneOfTenDefects();
    }

    @Provide
    Arbitrary<GeneratedDefinition> valid() {
        return DefinitionArbitraries.validDefinition();
    }

    /** A valid definition whose first action's {@code when} is replaced by an arbitrary JSONLogic tree. */
    @Provide
    Arbitrary<ObjectNode> withArbitraryExpression() {
        return Combinators.combine(DefinitionArbitraries.validDefinition(), ExpressionArbitraries.anyJsonLogic())
                .as((d, expr) -> {
                    ObjectNode doc = d.json().deepCopy();
                    ObjectNode action = (ObjectNode) doc.get("workflows").get(0).get("steps").get(0)
                            .get("actions").get(0);
                    action.set("when", expr);
                    return doc;
                });
    }

    /** Feature: skeleton-backend, Property 6: total. Validates: Requirements 2.4, 4.1, 4.2, 4.8, 18.5 */
    @Property(tries = 200)
    void totalOnValidAndMutatedDefinitions(@ForAll("valid") GeneratedDefinition valid,
                                           @ForAll("mutated") MutatedDefinition mutated) {
        ValidationReport v = validator.validate(valid.model());
        ValidationReport m = validator.validate(mutated.model());
        assertThat(v).isNotNull();
        assertThat(m).isNotNull();
        assertThat(m.violations()).isNotNull();
    }

    /** Totality over arbitrary JSONLogic trees embedded in a definition (where the model parses). */
    @Property(tries = 200)
    void totalOnArbitraryExpressions(@ForAll("withArbitraryExpression") ObjectNode doc) {
        AppDefinition model;
        try {
            model = parser.parse(doc);
        } catch (DefinitionParseException e) {
            Assume.that(false);
            return;
        }
        ValidationReport report = validator.validate(model);
        assertThat(report).isNotNull();
    }

    /** Feature: skeleton-backend, Property 6: deterministic. Validates: Requirements 18.6 */
    @Property(tries = 200)
    void deterministicAndSorted(@ForAll("mutated") MutatedDefinition mutated) {
        ValidationReport first = validator.validate(mutated.model());
        ValidationReport second = validator.validate(mutated.model());
        ValidationReport reparsed = validator.validate(parser.parse(mutated.json()));
        assertThat(second).isEqualTo(first);
        assertThat(reparsed).isEqualTo(first);
        assertThat(first.violations()).isSortedAccordingTo(Violation.ORDER);
    }

    /** Feature: skeleton-backend, Property 6: order-insensitive. Validates: Requirements 18.7 */
    @Property(tries = 200)
    void orderInsensitiveForCollections(@ForAll("mutated") MutatedDefinition mutated,
                                        @ForAll("seeds") long seed) {
        ObjectNode shuffled = mutated.json().deepCopy();
        Random rnd = new Random(seed);
        for (String key : List.of("roles", "entities", "forms", "views", "workflows")) {
            shuffle(shuffled.get(key), rnd);
        }
        for (JsonNode entity : shuffled.get("entities")) {
            shuffle(entity.get("fields"), rnd);
        }
        List<String> before = kinds(validator.validate(mutated.model()));
        List<String> after = kinds(validator.validate(parser.parse(shuffled)));
        assertThat(after).isEqualTo(before);
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs();
    }

    private static void shuffle(JsonNode node, Random rnd) {
        if (!(node instanceof ArrayNode array)) {
            return;
        }
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        Collections.shuffle(items, rnd);
        array.removeAll();
        items.forEach(array::add);
    }

    /** Sorted multiset of rule + message; paths are excluded on purpose (see class comment). */
    private static List<String> kinds(ValidationReport report) {
        return report.violations().stream().map(v -> v.rule() + " | " + v.message()).sorted().toList();
    }
}
