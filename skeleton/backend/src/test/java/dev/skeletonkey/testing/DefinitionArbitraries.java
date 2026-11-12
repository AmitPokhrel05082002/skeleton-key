package dev.skeletonkey.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.DefinitionParser;
import dev.skeletonkey.domain.validator.HandlerCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * Generators for valid App_Definition documents. Every generated document passes the JSON schema check and the
 * full Validator (structural, workflow graph and expression groups) with {@link #handlerCatalog()}.
 *
 * <p>Coverage: 1-20 roles, 1-10 entities (each with one field of every field type), 2-30 steps per workflow,
 * step types TASK, APPROVAL, AUTO and END (PARALLEL, JOIN and SUBWORKFLOW are not executed by the Router and are
 * rejected by the Validator), all five assignee strategies, and every declared status settable.
 * Generation is seed-driven, so a failing example is reproducible from its jqwik seed.
 */
public final class DefinitionArbitraries {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    /** Plugin handler names the generated documents may reference. */
    public static final List<String> HANDLER_NAMES = List.of("autoHandler", "notifyHandler", "pluginAction");

    /** Field type to the field code used for it on every generated entity. */
    public static final Map<String, String> FIELD_BY_TYPE = fieldByType();

    /** Codes of the fields every generated entity has, one per field type. */
    public static final List<String> FIELD_CODES = List.copyOf(FIELD_BY_TYPE.values());

    private static final List<String> SYSTEM_COLUMNS =
            List.of("$reference", "$status", "$step", "$assignee", "$createdAt", "$updatedAt", "$dueAt", "$createdBy");
    private static final List<String> STRATEGIES =
            List.of("ROLE_QUEUE", "LEAST_LOADED", "ROUND_ROBIN", "INITIATOR", "FROM_FIELD");
    private static final List<String> CATEGORIES = List.of("OPEN", "IN_PROGRESS", "DONE", "REJECTED");
    private static final List<String> FIELD_RULES = List.of("hidden", "readonly", "optional", "required");
    private static final List<String> PRESENCE = List.of("hidden", "optional", "required");

    private DefinitionArbitraries() {}

    /** A generated document in both representations. */
    public record GeneratedDefinition(ObjectNode json, AppDefinition model) {
        public String jsonText() {
            return json.toString();
        }
    }

    public static HandlerCatalog handlerCatalog() {
        return HANDLER_NAMES::contains;
    }

    /** Valid definitions as JSON and as the parsed model. */
    public static Arbitrary<GeneratedDefinition> validDefinition() {
        return validDefinition(20, 10, 30);
    }

    public static Arbitrary<GeneratedDefinition> validDefinition(int maxRoles, int maxEntities, int maxSteps) {
        return Combinators.combine(
                        Arbitraries.integers().between(1, maxRoles),
                        Arbitraries.integers().between(1, maxEntities),
                        Arbitraries.integers().between(2, maxSteps),
                        Arbitraries.longs())
                .as((roles, entities, steps, seed) -> build(new Random(seed), roles, entities, steps));
    }

    /** Valid definitions as raw JSON. */
    public static Arbitrary<JsonNode> validDefinitionJson() {
        return validDefinition().map(GeneratedDefinition::json);
    }

    /** Valid definitions as the parsed model. */
    public static Arbitrary<AppDefinition> validDefinitionModel() {
        return validDefinition().map(GeneratedDefinition::model);
    }

    /** Builds one valid document. Public so mutation and sequence generators can reuse it. */
    public static GeneratedDefinition build(Random rnd, int roleCount, int entityCount, int stepCount) {
        ObjectNode root = new Builder(rnd, roleCount, entityCount, stepCount).build();
        return new GeneratedDefinition(root, new DefinitionParser().parse(root));
    }

    private static Map<String, String> fieldByType() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("TEXT", "fText");
        m.put("TEXTAREA", "fTextarea");
        m.put("NUMBER", "fNumber");
        m.put("DECIMAL", "fDecimal");
        m.put("DATE", "fDate");
        m.put("DATETIME", "fDatetime");
        m.put("BOOLEAN", "fBoolean");
        m.put("SELECT", "fSelect");
        m.put("MULTISELECT", "fMultiselect");
        m.put("FILE", "fFile");
        m.put("USER", "fUser");
        m.put("REFERENCE", "fReference");
        m.put("EMAIL", "fEmail");
        m.put("PHONE", "fPhone");
        m.put("LOCATION", "fLocation");
        return Collections.unmodifiableMap(m);
    }

    private static final class Builder {
        private final Random rnd;
        private final int roleCount;
        private final int entityCount;
        private final int stepCount;
        private final List<String> roleCodes = new ArrayList<>();
        private final List<String> entityCodes = new ArrayList<>();
        private final List<List<String>> entityFields = new ArrayList<>();
        private final List<String> workflowEntities = new ArrayList<>();
        private final List<String> workflowCodes = new ArrayList<>();
        private int priority = 0;
        private boolean publicRoleMarked;

        Builder(Random rnd, int roleCount, int entityCount, int stepCount) {
            this.rnd = rnd;
            this.roleCount = roleCount;
            this.entityCount = entityCount;
            this.stepCount = stepCount;
        }

        ObjectNode build() {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("schemaVersion", 1);
            root.set("app", app());
            root.set("roles", roles());
            root.set("entities", entities());
            ArrayNode workflows = workflows();
            root.set("forms", forms());
            root.set("views", views());
            root.set("workflows", workflows);
            return root;
        }

        private ObjectNode app() {
            ObjectNode app = MAPPER.createObjectNode();
            app.put("code", "APP_" + (1 + rnd.nextInt(999)));
            app.put("name", "Generated app");
            if (rnd.nextBoolean()) {
                app.put("description", "Generated for property tests");
            }
            if (rnd.nextBoolean()) {
                ObjectNode b = app.putObject("branding");
                b.put("primaryColor", "#1a2b3c");
                b.put("theme", pick("light", "dark", "auto"));
            }
            return app;
        }

        private ArrayNode roles() {
            ArrayNode roles = MAPPER.createArrayNode();
            boolean hasPublic = roleCount > 1 && rnd.nextInt(3) == 0;
            publicRoleMarked = hasPublic;
            for (int i = 1; i <= roleCount; i++) {
                String code = "ROLE_" + i;
                roleCodes.add(code);
                ObjectNode r = roles.addObject();
                r.put("code", code);
                r.put("label", "Role " + i);
                if (hasPublic && i == roleCount) {
                    r.put("public", true);
                }
            }
            return roles;
        }

        private ArrayNode entities() {
            ArrayNode entities = MAPPER.createArrayNode();
            for (int i = 1; i <= entityCount; i++) {
                String code = "ENTITY_" + i;
                entityCodes.add(code);
                ObjectNode e = entities.addObject();
                e.put("code", code);
                e.put("label", "Entity " + i);
                if (rnd.nextBoolean()) {
                    e.put("titleField", "fText");
                }
                if (rnd.nextBoolean()) {
                    e.put("referencePrefix", "E" + (char) ('A' + i));
                }
                ArrayNode fields = e.putArray("fields");
                List<String> codes = new ArrayList<>();
                for (Map.Entry<String, String> t : FIELD_BY_TYPE.entrySet()) {
                    fields.add(field(t.getKey(), t.getValue()));
                    codes.add(t.getValue());
                }
                int extras = rnd.nextInt(4);
                for (int x = 1; x <= extras; x++) {
                    ObjectNode f = fields.addObject();
                    f.put("code", "extra" + x);
                    f.put("label", "Extra " + x);
                    f.put("type", "TEXT");
                    codes.add("extra" + x);
                }
                entityFields.add(codes);
            }
            return entities;
        }

        private ObjectNode field(String type, String code) {
            ObjectNode f = MAPPER.createObjectNode();
            f.put("code", code);
            f.put("label", "Field " + code);
            f.put("type", type);
            if (rnd.nextInt(4) == 0) {
                f.put("required", true);
            }
            if (rnd.nextInt(4) == 0) {
                f.put("help", "Help for " + code);
            }
            switch (type) {
                case "TEXT" -> {
                    if (rnd.nextBoolean()) {
                        f.put("maxLength", 1 + rnd.nextInt(200));
                    }
                    if (rnd.nextBoolean()) {
                        f.put("pattern", "^[A-Za-z0-9 ]*$");
                    }
                }
                case "NUMBER", "DECIMAL" -> {
                    int min = rnd.nextInt(50);
                    f.put("min", min);
                    f.put("max", min + rnd.nextInt(1000));
                }
                case "SELECT", "MULTISELECT" -> {
                    ArrayNode options = f.putArray("options");
                    int n = 1 + rnd.nextInt(4);
                    for (int i = 1; i <= n; i++) {
                        ObjectNode o = options.addObject();
                        o.put("value", "opt" + i);
                        o.put("label", "Option " + i);
                    }
                }
                case "REFERENCE" -> f.put("ref", "ENTITY_" + (1 + rnd.nextInt(entityCount)));
                case "FILE" -> {
                    f.put("accept", "image/*,application/pdf");
                    f.put("maxFiles", 1 + rnd.nextInt(5));
                }
                default -> { }
            }
            return f;
        }

        private ArrayNode forms() {
            ArrayNode forms = MAPPER.createArrayNode();
            // The public role, when present, is the last one.
            boolean publicRole = publicRoleMarked;
            for (int i = 0; i < entityCount; i++) {
                ObjectNode f = forms.addObject();
                f.put("code", "FORM_" + (i + 1));
                f.put("label", "Form " + (i + 1));
                f.put("entity", entityCodes.get(i));
                boolean isPublic = publicRole && rnd.nextInt(3) == 0;
                if (isPublic) {
                    f.put("public", true);
                } else {
                    ArrayNode roles = f.putArray("roles");
                    roles.add(roleCodes.get(rnd.nextInt(roleCount)));
                }
                int wf = workflowEntities.indexOf(entityCodes.get(i));
                if (wf >= 0 && rnd.nextInt(5) < 3) {
                    f.put("startsWorkflow", workflowCodes.get(wf));
                }
                List<String> shuffled = new ArrayList<>(entityFields.get(i));
                Collections.shuffle(shuffled, rnd);
                ArrayNode sections = f.putArray("sections");
                int sectionCount = 1 + rnd.nextInt(3);
                int per = Math.max(1, shuffled.size() / sectionCount);
                for (int s = 0; s < sectionCount; s++) {
                    int from = Math.min(s * per, shuffled.size() - 1);
                    int to = s == sectionCount - 1 ? shuffled.size() : Math.min(shuffled.size(), from + per);
                    ObjectNode sec = sections.addObject();
                    if (rnd.nextBoolean()) {
                        sec.put("title", "Section " + (s + 1));
                    }
                    sec.put("columns", 1 + rnd.nextInt(3));
                    ArrayNode fs = sec.putArray("fields");
                    shuffled.subList(from, Math.max(to, from + 1)).forEach(fs::add);
                }
            }
            return forms;
        }

        private ArrayNode views() {
            ArrayNode views = MAPPER.createArrayNode();
            for (int i = 0; i < entityCount; i++) {
                int n = rnd.nextInt(3);
                for (int j = 1; j <= n; j++) {
                    ObjectNode v = views.addObject();
                    v.put("code", "VIEW_" + (i + 1) + "_" + j);
                    if (rnd.nextBoolean()) {
                        v.put("label", "View " + (i + 1) + "." + j);
                    }
                    v.put("type", pick("TABLE", "KANBAN", "MY_TASKS"));
                    v.put("entity", entityCodes.get(i));
                    if (rnd.nextBoolean()) {
                        v.putArray("roles").add(roleCodes.get(rnd.nextInt(roleCount)));
                    }
                    List<String> columnPool = new ArrayList<>(entityFields.get(i));
                    columnPool.addAll(SYSTEM_COLUMNS);
                    ArrayNode columns = v.putArray("columns");
                    for (int c = 0; c < 1 + rnd.nextInt(4); c++) {
                        columns.add(columnPool.get(rnd.nextInt(columnPool.size())));
                    }
                    if (rnd.nextBoolean()) {
                        v.putArray("filters").add(columnPool.get(rnd.nextInt(columnPool.size())));
                    }
                    if (rnd.nextBoolean()) {
                        ObjectNode sort = v.putObject("defaultSort");
                        sort.put("field", columnPool.get(rnd.nextInt(columnPool.size())));
                        sort.put("direction", pick("ASC", "DESC"));
                    }
                    v.put("scope", pick("ALL", "MINE", "ASSIGNED_TO_ME", "MY_ROLE_QUEUE"));
                }
            }
            return views;
        }

        private ArrayNode workflows() {
            ArrayNode workflows = MAPPER.createArrayNode();
            int count = entityCount >= 2 && rnd.nextBoolean() ? 2 : 1;
            for (int k = 0; k < count; k++) {
                workflowCodes.add("WF_" + (k + 1));
                workflowEntities.add(entityCodes.get(k));
            }
            for (int k = 0; k < count; k++) {
                workflows.add(workflow(k));
            }
            return workflows;
        }

        private ObjectNode workflow(int k) {
            ObjectNode wf = MAPPER.createObjectNode();
            wf.put("code", workflowCodes.get(k));
            wf.put("label", "Workflow " + (k + 1));
            wf.put("entity", workflowEntities.get(k));
            wf.put("startStep", "STEP_1");

            int n = stepCount;
            int statusCount = 1 + rnd.nextInt(Math.min(n, 5));
            List<String> labels = new ArrayList<>();
            ArrayNode statuses = wf.putArray("statuses");
            for (int i = 0; i < statusCount; i++) {
                String label = "Status " + (i + 1);
                labels.add(label);
                ObjectNode s = statuses.addObject();
                s.put("label", label);
                s.put("category", i == 0 ? "OPEN" : pick(CATEGORIES));
                if (rnd.nextBoolean()) {
                    s.put("color", "#a1b2c3");
                }
            }
            wf.put("initialStatus", labels.get(0));

            List<String> stepCodes = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                stepCodes.add("STEP_" + i);
            }
            String[] types = new String[n];
            for (int i = 0; i < n; i++) {
                if (i == n - 1) {
                    types[i] = "END";
                } else if (i == 0) {
                    types[i] = "TASK";
                } else {
                    int r = rnd.nextInt(4);
                    types[i] = r == 0 ? "AUTO" : r == 1 ? "APPROVAL" : "TASK";
                }
            }
            String entity = workflowEntities.get(k);
            List<String> fields = entityFields.get(entityCodes.indexOf(entity));
            ArrayNode steps = wf.putArray("steps");
            List<List<String>> actionCodes = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                steps.add(step(i, n, types[i], stepCodes, labels, fields, actionCodes));
            }

            ArrayNode transitions = wf.putArray("transitions");
            int tCount = rnd.nextInt(Math.min(3, n) + 1);
            for (int t = 0; t < tCount && n > 1; t++) {
                int from = rnd.nextInt(n - 1);
                int to = from + 1 + rnd.nextInt(n - 1 - from);
                ObjectNode tr = transitions.addObject();
                tr.put("from", stepCodes.get(from));
                tr.put("to", stepCodes.get(to));
                List<String> acts = actionCodes.get(from);
                if (!acts.isEmpty() && rnd.nextBoolean()) {
                    tr.put("on", acts.get(rnd.nextInt(acts.size())));
                }
                if (rnd.nextInt(5) < 3) {
                    tr.set("when", condition(fields));
                }
                tr.put("priority", priority++);
                if (rnd.nextInt(10) < 3) {
                    tr.put("status", pick(labels));
                }
            }
            if (transitions.isEmpty()) {
                wf.remove("transitions");
            }
            return wf;
        }

        private ObjectNode step(int i, int n, String type, List<String> stepCodes, List<String> labels,
                                List<String> fields, List<List<String>> actionCodes) {
            ObjectNode s = MAPPER.createObjectNode();
            s.put("code", stepCodes.get(i));
            s.put("label", "Step " + (i + 1));
            s.put("type", type);
            List<String> myActions = new ArrayList<>();
            actionCodes.add(myActions);
            int statusCount = labels.size();

            if (type.equals("END")) {
                s.put("outcome", i < statusCount ? labels.get(i) : pick(labels));
            } else {
                if (i < statusCount) {
                    s.put("status", labels.get(i));
                } else if (rnd.nextInt(3) == 0) {
                    s.put("status", pick(labels));
                }
                if (rnd.nextInt(5) == 0) {
                    s.set("skipWhen", condition(fields));
                }
            }
            if (type.equals("TASK") || type.equals("APPROVAL")) {
                s.set("assignee", assignee());
                if (rnd.nextInt(5) < 2) {
                    int sla = 1 + rnd.nextInt(72);
                    s.put("slaHours", sla);
                    if (sla > 1 && rnd.nextBoolean()) {
                        s.put("remindBeforeHours", 1 + rnd.nextInt(sla - 1));
                    }
                    if (rnd.nextBoolean()) {
                        s.put("escalateTo", pick(roleCodes));
                    }
                }
                ArrayNode actions = s.putArray("actions");
                int ac = 1 + rnd.nextInt(3);
                for (int j = 1; j <= ac; j++) {
                    actions.add(action(i, j, n, stepCodes, labels, fields, myActions));
                }
            } else if (type.equals("AUTO") && rnd.nextBoolean()) {
                s.put("handler", pick(HANDLER_NAMES));
            }
            if (!type.equals("END") && rnd.nextBoolean()) {
                ObjectNode rules = s.putObject("fields");
                for (int r = 0; r < 1 + rnd.nextInt(4); r++) {
                    rules.put(pick(fields), pick(FIELD_RULES));
                }
            }
            events(s, "onEnter", fields);
            events(s, "onExit", fields);
            return s;
        }

        private ObjectNode assignee() {
            String strategy = pick(STRATEGIES);
            ObjectNode a = MAPPER.createObjectNode();
            a.put("strategy", strategy);
            switch (strategy) {
                case "ROLE_QUEUE", "LEAST_LOADED", "ROUND_ROBIN" -> a.put("role", pick(roleCodes));
                case "FROM_FIELD" -> a.put("field", FIELD_BY_TYPE.get("USER"));
                default -> { }
            }
            return a;
        }

        private ObjectNode action(int i, int j, int n, List<String> stepCodes, List<String> labels,
                                  List<String> fields, List<String> myActions) {
            String code = "ACT_" + (i + 1) + "_" + j;
            myActions.add(code);
            ObjectNode a = MAPPER.createObjectNode();
            a.put("code", code);
            a.put("label", "Action " + code);
            ArrayNode roles = a.putArray("roles");
            roles.add(pick(roleCodes));
            if (rnd.nextInt(3) == 0) {
                String second = pick(roleCodes);
                if (!second.equals(roles.get(0).asText())) {
                    roles.add(second);
                }
            }
            if (i + 1 < n && rnd.nextBoolean()) {
                a.put("to", stepCodes.get(i + 1 + rnd.nextInt(n - 1 - i)));
            }
            if (rnd.nextInt(10) < 3) {
                a.put("status", pick(labels));
            }
            if (rnd.nextBoolean()) {
                a.put("remarks", pick(PRESENCE));
            }
            if (rnd.nextBoolean()) {
                a.put("attachment", pick(PRESENCE));
            }
            if (rnd.nextBoolean()) {
                a.put("style", pick("primary", "default", "danger"));
            }
            if (rnd.nextInt(5) == 0) {
                a.put("confirm", "Are you sure?");
            }
            if (rnd.nextInt(10) < 3) {
                a.set("when", condition(fields));
            }
            if (rnd.nextInt(7) == 0) {
                a.put("handler", pick(HANDLER_NAMES));
            }
            return a;
        }

        private void events(ObjectNode step, String key, List<String> fields) {
            int count = rnd.nextInt(3);
            if (count == 0) {
                return;
            }
            ArrayNode events = step.putArray(key);
            for (int e = 0; e < count; e++) {
                ObjectNode ev = events.addObject();
                switch (rnd.nextInt(4)) {
                    case 0 -> {
                        ev.put("do", "NOTIFY");
                        ev.put("template", "template" + rnd.nextInt(5));
                        ArrayNode to = ev.putArray("to");
                        to.add(pick("INITIATOR", "ASSIGNEE", "ROLE:" + pick(roleCodes), "FIELD:fUser"));
                    }
                    case 1 -> {
                        ev.put("do", "SET_FIELD");
                        ev.put("field", pick(fields));
                        if (rnd.nextBoolean()) {
                            ev.put("value", "set" + rnd.nextInt(10));
                        } else {
                            ev.set("value", ExpressionArbitraries.generate(rnd, 1 + rnd.nextInt(3), FIELD_CODES));
                        }
                    }
                    case 2 -> {
                        ev.put("do", "WEBHOOK");
                        ev.put("url", "https://example.org/hook/" + rnd.nextInt(100));
                    }
                    default -> {
                        ev.put("do", "HANDLER");
                        ev.put("handler", pick(HANDLER_NAMES));
                    }
                }
                if (rnd.nextInt(3) == 0) {
                    ev.set("when", condition(fields));
                }
            }
        }

        private JsonNode condition(List<String> fields) {
            return ExpressionArbitraries.generate(rnd, 1 + rnd.nextInt(3), fields);
        }

        private String pick(String... options) {
            return options[rnd.nextInt(options.length)];
        }

        private String pick(List<String> options) {
            return options.get(rnd.nextInt(options.size()));
        }
    }
}
