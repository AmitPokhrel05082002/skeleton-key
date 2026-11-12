package dev.skeletonkey.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public record Step(String code, String label, Type type, Assignee assignee, String status, JsonNode skipWhen,
                   Double slaHours, Double remindBeforeHours, String escalateTo, Map<String, FieldRule> fields,
                   List<Action> actions, List<Event> onEnter, List<Event> onExit, List<String> branches,
                   String join, JoinMode mode, String workflow, List<String> copyFields, String handler,
                   String outcome) {
    public enum Type { TASK, APPROVAL, AUTO, PARALLEL, JOIN, SUBWORKFLOW, END }
    public enum FieldRule { HIDDEN, READONLY, OPTIONAL, REQUIRED }
    public enum JoinMode { ALL, ANY }

    public Step {
        fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
        actions = actions == null ? List.of() : List.copyOf(actions);
        onEnter = onEnter == null ? List.of() : List.copyOf(onEnter);
        onExit = onExit == null ? List.of() : List.copyOf(onExit);
        branches = branches == null ? List.of() : List.copyOf(branches);
        copyFields = copyFields == null ? List.of() : List.copyOf(copyFields);
    }

    public java.util.Optional<Action> action(String actionCode) {
        return actions.stream().filter(a -> a.code().equals(actionCode)).findFirst();
    }
}
