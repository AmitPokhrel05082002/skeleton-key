package dev.skeletonkey.domain.model;

import java.util.List;
import java.util.Optional;

/** @param tracking public status tracking config, or null when tracking is off for this entity */
public record Entity(String code, String label, String titleField, String referencePrefix, List<Field> fields,
                     Tracking tracking) {
    public Entity {
        fields = List.copyOf(fields);
    }

    /** Entity without tracking; keeps existing call sites unchanged. */
    public Entity(String code, String label, String titleField, String referencePrefix, List<Field> fields) {
        this(code, label, titleField, referencePrefix, fields, null);
    }

    public Optional<Field> field(String fieldCode) {
        return fields.stream().filter(f -> f.code().equals(fieldCode)).findFirst();
    }

    /** Public tracking: the citizen gives the reference number and the value of {@code contactField} (an EMAIL field). */
    public record Tracking(String contactField) {}
}
