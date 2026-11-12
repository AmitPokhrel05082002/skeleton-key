package dev.skeletonkey.domain.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.domain.model.DefinitionParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowGraphRulesTest {

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

    private ObjectNode wf(ObjectNode root) {
        return (ObjectNode) root.get("workflows").get(0);
    }

    private ObjectNode step(ObjectNode root, String code) {
        for (JsonNode s : wf(root).get("steps")) {
            if (s.get("code").asText().equals(code)) {
                return (ObjectNode) s;
            }
        }
        throw new IllegalArgumentException(code);
    }

    private ObjectNode obj(String json) throws Exception {
        return (ObjectNode) MAPPER.readTree(json);
    }

    private List<String> rules(ObjectNode root) {
        List<String> found = new ArrayList<>();
        new WorkflowGraphRules().check(parser.parse(root), v -> found.add(v.rule()));
        return found;
    }

    private List<Violation> violations(ObjectNode root) {
        List<Violation> found = new ArrayList<>();
        new WorkflowGraphRules().check(parser.parse(root), found::add);
        return found;
    }

    @Test
    void exampleHasNoGraphViolations() throws Exception {
        assertThat(violations(example())).isEmpty();
    }

    @Test
    void registeredInStructuralGroups() {
        assertThat(Validator.structuralGroups()).anyMatch(g -> g instanceof WorkflowGraphRules);
    }

    @Test
    void badStartStep() throws Exception {
        ObjectNode root = example();
        wf(root).put("startStep", "NOPE");
        assertThat(rules(root)).contains("BAD_START_STEP");
    }

    @Test
    void duplicateStepReportedOncePerCode() throws Exception {
        ObjectNode root = example();
        ArrayNode steps = (ArrayNode) wf(root).get("steps");
        steps.add(step(root, "RESOLVE").deepCopy());
        steps.add(step(root, "RESOLVE").deepCopy());
        assertThat(rules(root)).containsOnlyOnce("DUPLICATE_STEP");
    }

    @Test
    void noEndStepAndDeadEnds() throws Exception {
        ObjectNode root = example();
        ArrayNode steps = (ArrayNode) wf(root).get("steps");
        for (int i = steps.size() - 1; i >= 0; i--) {
            if ("END".equals(steps.get(i).get("type").asText())) {
                steps.remove(i);
            }
        }
        ((ObjectNode) steps.get(0)).set("actions", MAPPER.createArrayNode());
        List<String> r = rules(root);
        assertThat(r).contains("NO_END_STEP", "DEAD_END");
    }

    @Test
    void unreachableStepAndDanglingTargets() throws Exception {
        ObjectNode root = example();
        // SITE_VISIT is only reachable through the REVIEW->SITE_VISIT transition.
        ((ArrayNode) wf(root).get("transitions")).removeAll();
        // END_REJECTED loses its only inbound references; END steps have no next-in-order edge.
        ((ObjectNode) step(root, "TRIAGE").get("actions").get(1)).put("to", "END_RESOLVED");
        ((ObjectNode) step(root, "REVIEW").get("actions").get(1)).put("to", "END_RESOLVED");
        ObjectNode review = step(root, "REVIEW");
        ((ObjectNode) review.get("actions").get(0)).put("to", "GHOST");
        ((ArrayNode) wf(root).get("transitions")).add(obj("{\"from\":\"GHOST2\",\"to\":\"GHOST3\"}"));
        List<String> r = rules(root);
        assertThat(r).contains("UNREACHABLE_STEP");
        assertThat(r.stream().filter("DANGLING_TRANSITION"::equals).count()).isEqualTo(3);
    }

    @Test
    void nextInOrderFallbackKeepsStepReachable() throws Exception {
        ObjectNode root = example();
        ((ArrayNode) wf(root).get("transitions")).removeAll();
        // Without the transition SITE_VISIT is still reached as REVIEW's follower.
        assertThat(rules(root)).doesNotContain("UNREACHABLE_STEP");
    }

    @Test
    void badTransitionAction() throws Exception {
        ObjectNode root = example();
        ((ObjectNode) wf(root).get("transitions").get(0)).put("on", "MISSING");
        assertThat(rules(root)).containsOnlyOnce("BAD_TRANSITION_ACTION");
    }

    @Test
    void unknownStatusOnEveryReferenceKind() throws Exception {
        ObjectNode root = example();
        wf(root).put("initialStatus", "X1");
        ((ObjectNode) wf(root).get("transitions").get(0)).put("status", "X2");
        step(root, "TRIAGE").put("status", "X3");
        ((ObjectNode) step(root, "TRIAGE").get("actions").get(0)).put("status", "X4");
        step(root, "END_RESOLVED").put("outcome", "X5");
        assertThat(rules(root).stream().filter("UNKNOWN_STATUS"::equals).count()).isEqualTo(5);
    }

    @Test
    void unsetStatusWhenNothingSetsIt() throws Exception {
        ObjectNode root = example();
        ((ArrayNode) wf(root).get("statuses")).add(obj("{\"label\":\"Orphan\",\"category\":\"DONE\"}"));
        List<Violation> v = violations(root);
        assertThat(v).filteredOn(x -> x.rule().equals("UNSET_STATUS")).hasSize(1);
        assertThat(v.stream().filter(x -> x.rule().equals("UNSET_STATUS")).findFirst().orElseThrow().message())
                .contains("Orphan");
    }

    @Test
    void danglingAssigneeRoleAndNonUserField() throws Exception {
        ObjectNode root = example();
        ObjectNode a = (ObjectNode) step(root, "REVIEW").get("assignee");
        a.put("role", "GHOST_ROLE");
        ObjectNode site = step(root, "SITE_VISIT");
        site.set("assignee", obj("{\"strategy\":\"FROM_FIELD\",\"field\":\"noSuchField\"}"));
        assertThat(rules(root).stream().filter("DANGLING_ASSIGNEE"::equals).count()).isEqualTo(2);
    }

    @Test
    void danglingStepFieldsAndCopyFields() throws Exception {
        ObjectNode root = example();
        ((ObjectNode) step(root, "TRIAGE").get("fields")).put("ghostField", "optional");
        step(root, "REVIEW").set("copyFields", MAPPER.createArrayNode().add("ghostCopy"));
        assertThat(rules(root).stream().filter("DANGLING_STEP_FIELD"::equals).count()).isEqualTo(2);
    }

    @Test
    void danglingActionRole() throws Exception {
        ObjectNode root = example();
        ((ArrayNode) step(root, "TRIAGE").get("actions").get(0).get("roles")).add("GHOST_ROLE");
        assertThat(rules(root)).containsOnlyOnce("DANGLING_ACTION_ROLE");
    }

    @Test
    void ambiguousPriorityOncePerGroup() throws Exception {
        ObjectNode root = example();
        ArrayNode ts = (ArrayNode) wf(root).get("transitions");
        ts.add(obj("{\"from\":\"REVIEW\",\"to\":\"RESOLVE\",\"on\":\"APPROVE\",\"priority\":10}"));
        ts.add(obj("{\"from\":\"REVIEW\",\"to\":\"RESOLVE\",\"on\":\"APPROVE\",\"priority\":10}"));
        ts.add(obj("{\"from\":\"REVIEW\",\"to\":\"RESOLVE\",\"on\":\"APPROVE\",\"priority\":20}"));
        assertThat(rules(root)).containsOnlyOnce("AMBIGUOUS_PRIORITY");
    }

    @Test
    void parallelJoinAndSubworkflowAreUnsupportedAndJoinMismatchDetected() throws Exception {
        ObjectNode root = example();
        ArrayNode steps = (ArrayNode) wf(root).get("steps");
        steps.add(obj("{\"code\":\"PAR\",\"type\":\"PARALLEL\",\"branches\":[\"REVIEW\",\"NOWHERE\"],\"join\":\"MISSING_JOIN\"}"));
        steps.add(obj("{\"code\":\"JN\",\"type\":\"JOIN\",\"mode\":\"ALL\"}"));
        steps.add(obj("{\"code\":\"SUB\",\"type\":\"SUBWORKFLOW\",\"workflow\":\"GRIEVANCE_FLOW\"}"));
        List<String> r = rules(root);
        assertThat(r.stream().filter("UNSUPPORTED_STEP"::equals).count()).isEqualTo(3);
        assertThat(r.stream().filter("DANGLING_BRANCH"::equals).count()).isEqualTo(2);
        assertThat(r).containsOnlyOnce("JOIN_MISMATCH");
    }

    @Test
    void joinNamedByExactlyOneParallelIsNotMismatch() throws Exception {
        ObjectNode root = example();
        ArrayNode steps = (ArrayNode) wf(root).get("steps");
        steps.add(obj("{\"code\":\"PAR\",\"type\":\"PARALLEL\",\"branches\":[\"REVIEW\",\"SITE_VISIT\"],\"join\":\"JN\"}"));
        steps.add(obj("{\"code\":\"JN\",\"type\":\"JOIN\",\"mode\":\"ALL\"}"));
        assertThat(rules(root)).doesNotContain("JOIN_MISMATCH");
    }
}
