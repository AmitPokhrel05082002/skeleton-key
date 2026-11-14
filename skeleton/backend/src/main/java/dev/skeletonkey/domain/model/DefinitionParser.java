package dev.skeletonkey.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;

/**
 * Maps a schema-shaped JsonNode to the immutable model. It checks shape only (types, enum names, required
 * properties); cross-reference rules belong to the Validator. Workflows carry no version field.
 */
@Component
public class DefinitionParser {

    public AppDefinition parse(JsonNode root) {
        if (root == null || !root.isObject()) throw new DefinitionParseException("$: expected an object");
        JsonNode app = obj(root, "app", "$");
        JsonNode branding = app.get("branding");
        AppInfo info = new AppInfo(str(app, "code", "$.app"), str(app, "name", "$.app"), optStr(app, "description"),
                branding == null || !branding.isObject() ? null
                        : new Branding(optStr(branding, "primaryColor"), optStr(branding, "logo"), optStr(branding, "theme")));
        return new AppDefinition(info,
                list(root, "roles", "$", true, (n, p) -> new Role(str(n, "code", p), str(n, "label", p), bool(n, "public", false))),
                list(root, "entities", "$", true, this::entity),
                list(root, "forms", "$", false, this::form),
                list(root, "views", "$", false, this::view),
                list(root, "workflows", "$", true, this::workflow));
    }

    private Entity entity(JsonNode n, String p) {
        return new Entity(str(n, "code", p), str(n, "label", p), optStr(n, "titleField"), optStr(n, "referencePrefix"),
                list(n, "fields", p, true, this::field));
    }

    private Field field(JsonNode n, String p) {
        return new Field(str(n, "code", p), str(n, "label", p), enumOf(Field.Type.class, n, "type", p, null),
                bool(n, "required", false), optStr(n, "help"), n.has("default") ? n.get("default") : null,
                list(n, "options", p, false, (o, op) -> new Option(str(o, "value", op), str(o, "label", op))),
                optStr(n, "ref"), optDouble(n, "min"), optDouble(n, "max"), optInt(n, "maxLength"),
                optStr(n, "pattern"), optStr(n, "accept"), optInt(n, "maxFiles"),
                optStr(n, "widget"));
    }

    private Form form(JsonNode n, String p) {
        return new Form(str(n, "code", p), optStr(n, "label"), str(n, "entity", p), bool(n, "public", false),
                strings(n, "roles"), optStr(n, "startsWorkflow"),
                list(n, "sections", p, true, (s, sp) -> new Form.Section(optStr(s, "title"),
                        s.has("columns") ? s.get("columns").asInt(1) : 1, strings(s, "fields"))));
    }

    private View view(JsonNode n, String p) {
        JsonNode sort = n.get("defaultSort");
        View.Sort ds = sort == null ? null : new View.Sort(str(sort, "field", p + ".defaultSort"),
                enumOf(View.Direction.class, sort, "direction", p + ".defaultSort", View.Direction.DESC));
        return new View(str(n, "code", p), optStr(n, "label"), enumOf(View.Type.class, n, "type", p, null),
                str(n, "entity", p), strings(n, "roles"), strings(n, "columns"), strings(n, "filters"), ds,
                enumOf(View.Scope.class, n, "scope", p, View.Scope.ALL));
    }

    private Workflow workflow(JsonNode n, String p) {
        return new Workflow(str(n, "code", p), optStr(n, "label"), str(n, "entity", p), str(n, "startStep", p),
                optStr(n, "initialStatus"),
                list(n, "statuses", p, true, (s, sp) -> new Status(str(s, "label", sp),
                        enumOf(Status.Category.class, s, "category", sp, null), optStr(s, "color"))),
                list(n, "steps", p, true, this::step),
                list(n, "transitions", p, false, (t, tp) -> new Transition(str(t, "from", tp), str(t, "to", tp),
                        optStr(t, "on"), expr(t, "when"),
                        t.has("priority") ? t.get("priority").asInt() : Transition.DEFAULT_PRIORITY,
                        optStr(t, "status"))));
    }

    private Step step(JsonNode n, String p) {
        Map<String, Step.FieldRule> rules = new LinkedHashMap<>();
        JsonNode f = n.get("fields");
        if (f != null && f.isObject()) {
            f.fields().forEachRemaining(e -> rules.put(e.getKey(),
                    parseEnum(Step.FieldRule.class, e.getValue().asText(), p + ".fields." + e.getKey())));
        }
        JsonNode a = n.get("assignee");
        Assignee assignee = a == null ? null
                : new Assignee(enumOf(Assignee.Strategy.class, a, "strategy", p + ".assignee", null),
                        optStr(a, "role"), optStr(a, "field"));
        return new Step(str(n, "code", p), optStr(n, "label"), enumOf(Step.Type.class, n, "type", p, null), assignee,
                optStr(n, "status"), expr(n, "skipWhen"), optDouble(n, "slaHours"), optDouble(n, "remindBeforeHours"),
                optStr(n, "escalateTo"), rules, list(n, "actions", p, false, this::action),
                list(n, "onEnter", p, false, this::event), list(n, "onExit", p, false, this::event),
                strings(n, "branches"), optStr(n, "join"), enumOf(Step.JoinMode.class, n, "mode", p, null),
                optStr(n, "workflow"), strings(n, "copyFields"), optStr(n, "handler"), optStr(n, "outcome"));
    }

    private Action action(JsonNode n, String p) {
        return new Action(str(n, "code", p), str(n, "label", p), strings(n, "roles"), optStr(n, "to"),
                optStr(n, "status"), enumOf(Action.Presence.class, n, "remarks", p, null),
                enumOf(Action.Presence.class, n, "attachment", p, null), enumOf(Action.Style.class, n, "style", p, null),
                optStr(n, "confirm"), expr(n, "when"), optStr(n, "handler"));
    }

    private Event event(JsonNode n, String p) {
        String kind = str(n, "do", p);
        JsonNode when = expr(n, "when");
        return switch (kind) {
            case "NOTIFY" -> new Event.Notify(when, str(n, "template", p), strings(n, "to"));
            case "SET_FIELD" -> {
                if (!n.has("value")) throw new DefinitionParseException(p + ": missing 'value'");
                yield new Event.SetField(when, str(n, "field", p), n.get("value"));
            }
            case "WEBHOOK" -> new Event.Webhook(when, str(n, "url", p));
            case "HANDLER" -> new Event.HandlerCall(when, str(n, "handler", p));
            default -> throw new DefinitionParseException(p + ".do: unknown event kind '" + kind + "'");
        };
    }

    // ---- helpers ----

    private static JsonNode expr(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v;
    }

    private static JsonNode obj(JsonNode n, String key, String p) {
        JsonNode v = n.get(key);
        if (v == null || !v.isObject()) throw new DefinitionParseException(p + "." + key + ": expected an object");
        return v;
    }

    private static String str(JsonNode n, String key, String p) {
        JsonNode v = n.get(key);
        if (v == null || !v.isTextual()) throw new DefinitionParseException(p + "." + key + ": expected a string");
        return v.asText();
    }

    private static String optStr(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static boolean bool(JsonNode n, String key, boolean dflt) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? dflt : v.asBoolean(dflt);
    }

    private static Double optDouble(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || !v.isNumber() ? null : v.asDouble();
    }

    private static Integer optInt(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || !v.isNumber() ? null : v.asInt();
    }

    private static List<String> strings(JsonNode n, String key) {
        List<String> out = new ArrayList<>();
        JsonNode v = n.get(key);
        if (v != null && v.isArray()) v.forEach(e -> out.add(e.asText()));
        return out;
    }

    private static <T> List<T> list(JsonNode n, String key, String p, boolean required,
                                    BiFunction<JsonNode, String, T> mapper) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            if (required) throw new DefinitionParseException(p + "." + key + ": missing");
            return List.of();
        }
        if (!v.isArray()) throw new DefinitionParseException(p + "." + key + ": expected an array");
        List<T> out = new ArrayList<>();
        for (int i = 0; i < v.size(); i++) {
            JsonNode item = v.get(i);
            String ip = p + "." + key + "[" + i + "]";
            if (!item.isObject()) throw new DefinitionParseException(ip + ": expected an object");
            out.add(mapper.apply(item, ip));
        }
        return out;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, JsonNode n, String key, String p, E dflt) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            if (dflt == null && isRequiredEnum(type)) throw new DefinitionParseException(p + "." + key + ": missing");
            return dflt;
        }
        return parseEnum(type, v.asText(), p + "." + key);
    }

    /** Enums whose property is mandatory in the schema; the rest are optional and map to null when absent. */
    private static boolean isRequiredEnum(Class<?> type) {
        return type == Field.Type.class || type == Step.Type.class || type == View.Type.class
                || type == Status.Category.class || type == Assignee.Strategy.class;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String p) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new DefinitionParseException(p + ": unknown value '" + value + "'");
        }
    }
}
