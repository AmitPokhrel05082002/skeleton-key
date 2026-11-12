package dev.skeletonkey.plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Field changes (field code to new value) returned by a plugin handler; empty means no changes. */
public record HandlerResult(Map<String, Object> fieldChanges) {
    public HandlerResult {
        fieldChanges = fieldChanges == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(fieldChanges));
    }

    public static HandlerResult none() {
        return new HandlerResult(Map.of());
    }

    public static HandlerResult of(Map<String, Object> fieldChanges) {
        return new HandlerResult(fieldChanges);
    }
}
