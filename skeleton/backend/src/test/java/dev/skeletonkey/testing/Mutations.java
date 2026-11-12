package dev.skeletonkey.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.testing.DefinitionArbitraries.GeneratedDefinition;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * Injects exactly one defect into a valid definition and records the Validator rule code that must report it.
 * Every mutated document still satisfies the JSON schema, so the defect is found by the publish-time Validator
 * and not by the loader. Defects target the first workflow, which always starts with a TASK step that has an
 * action, and the first entity.
 */
public final class Mutations {

    /** The ten defect kinds. */
    public enum Defect {
        DUPLICATE_ROLE_CODE("DUPLICATE_CODE"),
        TWO_PUBLIC_ROLES("MULTIPLE_PUBLIC_ROLES"),
        DANGLING_REFERENCE_ENTITY("DANGLING_REF_ENTITY"),
        MIN_GREATER_THAN_MAX("MIN_GT_MAX"),
        INVALID_PATTERN("BAD_PATTERN"),
        FORM_NAMES_MISSING_ENTITY("DANGLING_FORM_REF"),
        MISSING_START_STEP("BAD_START_STEP"),
        UNKNOWN_ACTION_HANDLER("UNKNOWN_HANDLER"),
        UNSUPPORTED_OPERATOR("EXPR_OPERATOR"),
        UNSETTABLE_STATUS("UNSET_STATUS");

        private final String expectedRule;

        Defect(String expectedRule) {
            this.expectedRule = expectedRule;
        }

        public String expectedRule() {
            return expectedRule;
        }
    }

    /** A document with one injected defect. {@code json} is the mutated copy; the valid original is untouched. */
    public record MutatedDefinition(ObjectNode json, AppDefinition model, Defect defect, String expectedRule,
                                    GeneratedDefinition original) {
        public String jsonText() {
            return json.toString();
        }
    }

    private Mutations() {}

    /** A valid definition with exactly one of the ten defects injected. */
    public static Arbitrary<MutatedDefinition> oneOfTenDefects() {
        return Combinators.combine(DefinitionArbitraries.validDefinition(),
                        Arbitraries.of(Defect.values()))
                .as(Mutations::apply);
    }

    /** The same, for one fixed defect kind. */
    public static Arbitrary<MutatedDefinition> withDefect(Defect defect) {
        return DefinitionArbitraries.validDefinition().map(d -> apply(d, defect));
    }

    public static MutatedDefinition apply(GeneratedDefinition valid, Defect defect) {
        ObjectNode doc = valid.json().deepCopy();
        switch (defect) {
            case DUPLICATE_ROLE_CODE -> {
                ObjectNode copy = ((ObjectNode) doc.get("roles").get(0)).deepCopy();
                copy.remove("public");
                ((ArrayNode) doc.get("roles")).add(copy);
            }
            case TWO_PUBLIC_ROLES -> {
                ArrayNode roles = (ArrayNode) doc.get("roles");
                boolean hasPublic = false;
                for (JsonNode r : roles) {
                    hasPublic |= r.path("public").asBoolean(false);
                }
                if (!hasPublic) {
                    ((ObjectNode) roles.get(0)).put("public", true);
                }
                ObjectNode extra = roles.addObject();
                extra.put("code", "EXTRA_PUBLIC");
                extra.put("label", "Extra public role");
                extra.put("public", true);
            }
            case DANGLING_REFERENCE_ENTITY -> field(doc, "fReference").put("ref", "NO_SUCH_ENTITY");
            case MIN_GREATER_THAN_MAX -> {
                ObjectNode f = field(doc, "fNumber");
                f.put("min", 10);
                f.put("max", 1);
            }
            case INVALID_PATTERN -> field(doc, "fText").put("pattern", "[unclosed");
            case FORM_NAMES_MISSING_ENTITY -> ((ObjectNode) doc.get("forms").get(0)).put("entity", "NO_SUCH_ENTITY");
            case MISSING_START_STEP -> firstWorkflow(doc).put("startStep", "NO_SUCH_STEP");
            case UNKNOWN_ACTION_HANDLER -> firstAction(doc).put("handler", "noSuchHandler");
            case UNSUPPORTED_OPERATOR -> {
                ObjectNode when = DefinitionArbitraries.MAPPER.createObjectNode();
                when.putArray("noSuchOperator").add(1).add(2);
                firstAction(doc).set("when", when);
            }
            case UNSETTABLE_STATUS -> {
                ObjectNode s = ((ArrayNode) firstWorkflow(doc).get("statuses")).addObject();
                s.put("label", "Orphan status");
                s.put("category", "OPEN");
            }
            default -> throw new IllegalStateException(defect.name());
        }
        return new MutatedDefinition(doc, new DefinitionParser().parse(doc), defect, defect.expectedRule(), valid);
    }

    private static ObjectNode firstWorkflow(ObjectNode doc) {
        return (ObjectNode) doc.get("workflows").get(0);
    }

    private static ObjectNode firstAction(ObjectNode doc) {
        return (ObjectNode) firstWorkflow(doc).get("steps").get(0).get("actions").get(0);
    }

    private static ObjectNode field(ObjectNode doc, String code) {
        for (JsonNode f : doc.get("entities").get(0).get("fields")) {
            if (f.get("code").asText().equals(code)) {
                return (ObjectNode) f;
            }
        }
        throw new IllegalStateException("field " + code);
    }
}
