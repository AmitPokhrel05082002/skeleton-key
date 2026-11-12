package dev.skeletonkey.domain.model;

import java.util.List;
import java.util.Optional;

public record Entity(String code, String label, String titleField, String referencePrefix, List<Field> fields) {
    public Entity {
        fields = List.copyOf(fields);
    }

    public Optional<Field> field(String fieldCode) {
        return fields.stream().filter(f -> f.code().equals(fieldCode)).findFirst();
    }
}
