package dev.skeletonkey.domain.model;

import java.util.List;

public record View(String code, String label, Type type, String entity, List<String> roles, List<String> columns,
                   List<String> filters, Sort defaultSort, Scope scope) {
    public enum Type { TABLE, KANBAN, MY_TASKS }
    public enum Scope { ALL, MINE, ASSIGNED_TO_ME, MY_ROLE_QUEUE }
    public enum Direction { ASC, DESC }

    public record Sort(String field, Direction direction) {}

    public View {
        roles = roles == null ? List.of() : List.copyOf(roles);
        columns = columns == null ? List.of() : List.copyOf(columns);
        filters = filters == null ? List.of() : List.copyOf(filters);
    }
}
