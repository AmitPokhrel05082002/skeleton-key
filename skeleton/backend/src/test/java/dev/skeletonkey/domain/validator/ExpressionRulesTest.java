package dev.skeletonkey.domain.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.DefinitionParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ExpressionRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DefinitionParser parser = new DefinitionParser();

    private ObjectNode example() throws Exception {
        for (String p : new String[] {"../../examples/grievance.app.json", "examples/grievance.app.json"}) {
            if (Files.exists(Path.of(p))) {
                return (ObjectNode) MAPPER.readTree(Files.readString(Path.of(p)));
            }
        }
        throw new IllegalStateException("grievance example not found");
    }

    private List<Violation> run(ObjectNode root, HandlerCatalog catalog) {
        AppDefinition def = parser.parse(root);
        List<Violation> found = new ArrayList<>();
        Consumer<Violation> sink = found::add;
        new ExpressionRules(catalog).check(def, sink);
        return found;
    }

    private ObjectNode step0(ObjectNode root) {
        return (ObjectNode) root.get("workflows").get(0).get("steps").get(0);
    }

    private ObjectNode workflow0(ObjectNode root) {
        return (ObjectNode) root.get("workflows").get(0);
    }

    private JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void exampleHasNoExpressionViolationsGivenItsHandlers() throws Exception {
        ObjectNode root = example();
        assertThat(run(root, name -> true)).isEmpty();
    }

    @Test
    void reportsParseOperatorRootAndFieldAndAllAtOnce() throws Exception {
        ObjectNode root = example();
        String entityCode = workflow0(root).get("entity").asText();
        step0(root).set("skipWhen", json("{\"and\":[{\"bogus\":[1]},{\"==\":[{\"var\":\"foo.x\"},1]},"
                + "{\"==\":[{\"var\":\"data.noSuchField\"},1]},{\"a\":1,\"b\":2}]}"));
        List<Violation> v = run(root, name -> true);
        String base = "/workflows/0/steps/0/skipWhen";
        assertThat(v).extracting(Violation::rule)
                .contains("EXPR_OPERATOR", "EXPR_VAR_ROOT", "EXPR_FIELD", "EXPR_PARSE");
        assertThat(v).filteredOn(x -> x.rule().equals("EXPR_OPERATOR"))
                .singleElement().satisfies(x -> {
                    assertThat(x.path()).startsWith(base);
                    assertThat(x.message()).contains("bogus");
                });
        assertThat(v).filteredOn(x -> x.rule().equals("EXPR_VAR_ROOT"))
                .singleElement().satisfies(x -> assertThat(x.message()).contains("foo"));
        assertThat(v).filteredOn(x -> x.rule().equals("EXPR_FIELD"))
                .singleElement().satisfies(x -> assertThat(x.message())
                        .contains("noSuchField").contains(entityCode));
    }

    @Test
    void childOutcomeAndValidFieldsAreAccepted() throws Exception {
        ObjectNode root = example();
        String field = root.get("entities").get(0).get("fields").get(0).get("code").asText();
        step0(root).set("skipWhen", json("{\"or\":[{\"==\":[{\"var\":\"data.$childOutcome\"},\"x\"]},"
                + "{\"==\":[{\"var\":\"data." + field + "\"},1]},{\"in\":[{\"var\":\"user.id\"},[1]]}]}"));
        assertThat(run(root, name -> true)).isEmpty();
    }

    @Test
    void transitionExpressionIsChecked() throws Exception {
        ObjectNode root = example();
        var transitions = MAPPER.createArrayNode();
        transitions.add(json("{\"from\":\"" + step0(root).get("code").asText() + "\",\"to\":\""
                + workflow0(root).get("steps").get(1).get("code").asText()
                + "\",\"when\":{\"eval\":[1]}}"));
        workflow0(root).set("transitions", transitions);
        assertThat(run(root, name -> true)).extracting(Violation::rule, Violation::path)
                .contains(org.assertj.core.groups.Tuple.tuple("EXPR_OPERATOR", "/workflows/0/transitions/0/when"));
    }

    @Test
    void unknownHandlerOnAutoStepAndSetFieldUnknownAreReported() throws Exception {
        ObjectNode root = example();
        ObjectNode step = step0(root);
        step.put("handler", "ghostHandler");
        var events = MAPPER.createArrayNode();
        events.add(json("{\"do\":\"SET_FIELD\",\"field\":\"nope\",\"value\":1}"));
        events.add(json("{\"do\":\"HANDLER\",\"handler\":\"ghost2\"}"));
        step.set("onEnter", events);
        List<Violation> v = run(root, name -> false);
        assertThat(v).anySatisfy(x -> {
            assertThat(x.rule()).isEqualTo("UNKNOWN_HANDLER");
            assertThat(x.path()).isEqualTo("/workflows/0/steps/0/handler");
            assertThat(x.message()).contains("ghostHandler");
        });
        assertThat(v).anySatisfy(x -> {
            assertThat(x.rule()).isEqualTo("SET_FIELD_UNKNOWN");
            assertThat(x.path()).isEqualTo("/workflows/0/steps/0/onEnter/0");
            assertThat(x.message()).contains("nope");
        });
        assertThat(v).anySatisfy(x -> {
            assertThat(x.rule()).isEqualTo("UNKNOWN_HANDLER");
            assertThat(x.path()).isEqualTo("/workflows/0/steps/0/onEnter/1/handler");
        });
    }

    @Test
    void validatorWithCatalogRunsExpressionRules() throws Exception {
        ObjectNode root = example();
        step0(root).set("skipWhen", json("{\"bogus\":[1]}"));
        ValidationReport report = new Validator(HandlerCatalog.empty()).validate(parser.parse(root));
        assertThat(report.violations()).extracting(Violation::rule).contains("EXPR_OPERATOR");
    }
}
