package dev.skeletonkey.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record Field(String code, String label, Type type, boolean required, String help, JsonNode defaultValue,
                    List<Option> options, String ref, Double min, Double max, Integer maxLength,
                    String pattern, String accept, Integer maxFiles) {
    public enum Type {
        TEXT, TEXTAREA, NUMBER, DECIMAL, DATE, DATETIME, BOOLEAN, SELECT, MULTISELECT, FILE, USER,
        REFERENCE, EMAIL, PHONE, LOCATION
    }

    public Field {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
