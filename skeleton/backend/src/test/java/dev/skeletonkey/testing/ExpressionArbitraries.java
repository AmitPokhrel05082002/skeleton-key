package dev.skeletonkey.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * Generators for JSONLogic expressions that use only allow-listed operators and valid {@code var}
 * roots, so they pass the expression rules of the Validator. Generation is driven by a seed, which keeps
 * a failing example reproducible; jqwik shrinks the depth and the seed.
 */
public final class ExpressionArbitraries {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** Field codes present on every entity produced by {@link DefinitionArbitraries}. */
    public static final List<String> DEFAULT_FIELDS = DefinitionArbitraries.FIELD_CODES;

    private static final List<String> OTHER_VARS =
            List.of("instance.status", "instance.step", "instance.createdAt", "user.id", "user.roles");

    private ExpressionArbitraries() {}

    /**
     * Any allow-listed JSONLogic tree with operation depth 0 to 10. Depth 0 is a scalar literal; the root may
     * therefore be a scalar. Use {@link #condition(int)} where a definition needs an object or boolean.
     */
    public static Arbitrary<JsonNode> anyJsonLogic() {
        return Combinators.combine(Arbitraries.integers().between(0, 10), Arbitraries.longs())
                .as((depth, seed) -> generate(new Random(seed), depth, DEFAULT_FIELDS));
    }

    /** Like {@link #anyJsonLogic()} with a fixed maximum depth. */
    public static Arbitrary<JsonNode> anyJsonLogic(int maxDepth) {
        return Combinators.combine(Arbitraries.integers().between(0, maxDepth), Arbitraries.longs())
                .as((depth, seed) -> generate(new Random(seed), depth, DEFAULT_FIELDS));
    }

    /** An expression whose root is an operation object (schema type object), depth 1 to {@code maxDepth}. */
    public static Arbitrary<JsonNode> condition(int maxDepth) {
        return Combinators.combine(Arbitraries.integers().between(1, Math.max(1, maxDepth)), Arbitraries.longs())
                .as((depth, seed) -> generate(new Random(seed), depth, DEFAULT_FIELDS));
    }

    /**
     * Builds one expression. The tree has operation depth at most {@code depth}; at depth 0 the result is a
     * scalar literal. {@code var} paths name only the given fields under {@code data}.
     */
    public static JsonNode generate(Random rnd, int depth, List<String> fields) {
        if (depth <= 0) {
            return literal(rnd);
        }
        int pick = rnd.nextInt(14);
        return switch (pick) {
            case 0 -> op("!", array(child(rnd, depth, fields)));
            case 1 -> op("!!", array(child(rnd, depth, fields)));
            case 2 -> op(choose(rnd, "==", "!=", "===", "!=="), array(child(rnd, depth, fields), child(rnd, depth, fields)));
            case 3 -> op(choose(rnd, ">", ">=", "<", "<="), array(child(rnd, depth, fields), child(rnd, depth, fields)));
            case 4 -> op(choose(rnd, "and", "or"), nary(rnd, depth, fields, 2, 3));
            case 5 -> op(choose(rnd, "+", "*", "min", "max"), nary(rnd, depth, fields, 1, 3));
            case 6 -> op(choose(rnd, "-", "/", "%"), array(child(rnd, depth, fields), child(rnd, depth, fields)));
            case 7 -> op("if", array(child(rnd, depth, fields), child(rnd, depth, fields), child(rnd, depth, fields)));
            case 8 -> op("in", array(child(rnd, depth, fields), child(rnd, depth, fields)));
            case 9 -> op(choose(rnd, "cat", "merge"), nary(rnd, depth, fields, 1, 3));
            case 10 -> op("missing", missingArgs(rnd, fields));
            case 11 -> op("missing_some", array(F.numberNode(1), missingArgs(rnd, fields)));
            case 12 -> op("var", array(F.textNode(varPath(rnd, fields)), child(rnd, depth, fields)));
            default -> op("var", F.textNode(varPath(rnd, fields)));
        };
    }

    /** Operation depth of a tree built by {@link #generate}: scalars are 0, each operation adds one. */
    public static int depthOf(JsonNode node) {
        if (node.isArray()) {
            int max = 0;
            for (JsonNode n : node) {
                max = Math.max(max, depthOf(n));
            }
            return max;
        }
        if (!node.isObject()) {
            return 0;
        }
        int max = 0;
        for (JsonNode v : node) {
            max = Math.max(max, depthOf(v));
        }
        return max + 1;
    }

    // ---- helpers ----

    private static JsonNode child(Random rnd, int depth, List<String> fields) {
        return generate(rnd, rnd.nextInt(depth), fields);
    }

    private static ArrayNode nary(Random rnd, int depth, List<String> fields, int min, int max) {
        int n = min + rnd.nextInt(max - min + 1);
        ArrayNode a = F.arrayNode();
        for (int i = 0; i < n; i++) {
            a.add(child(rnd, depth, fields));
        }
        return a;
    }

    private static ArrayNode array(JsonNode... items) {
        ArrayNode a = F.arrayNode();
        for (JsonNode i : items) {
            a.add(i);
        }
        return a;
    }

    private static ArrayNode missingArgs(Random rnd, List<String> fields) {
        ArrayNode a = F.arrayNode();
        a.add(fields.get(rnd.nextInt(fields.size())));
        a.add(fields.get(rnd.nextInt(fields.size())));
        return a;
    }

    private static ObjectNode op(String name, JsonNode args) {
        ObjectNode o = F.objectNode();
        o.set(name, args);
        return o;
    }

    private static String choose(Random rnd, String... options) {
        return options[rnd.nextInt(options.length)];
    }

    private static String varPath(Random rnd, List<String> fields) {
        if (rnd.nextInt(4) == 0) {
            return OTHER_VARS.get(rnd.nextInt(OTHER_VARS.size()));
        }
        return "data." + fields.get(rnd.nextInt(fields.size()));
    }

    private static JsonNode literal(Random rnd) {
        return switch (rnd.nextInt(5)) {
            case 0 -> F.numberNode(rnd.nextInt(2001) - 1000);
            case 1 -> F.textNode("v" + rnd.nextInt(50));
            case 2 -> F.booleanNode(rnd.nextBoolean());
            case 3 -> F.nullNode();
            default -> F.numberNode(rnd.nextInt(100));
        };
    }
}
