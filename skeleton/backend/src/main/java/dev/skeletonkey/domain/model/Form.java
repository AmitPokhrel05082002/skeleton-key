package dev.skeletonkey.domain.model;

import java.util.List;

public record Form(String code, String label, String entity, boolean isPublic, List<String> roles,
                   String startsWorkflow, List<Section> sections) {
    public record Section(String title, int columns, List<String> fields) {
        public Section { fields = List.copyOf(fields); }
    }

    public Form {
        roles = roles == null ? List.of() : List.copyOf(roles);
        sections = List.copyOf(sections);
    }
}
