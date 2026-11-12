package dev.skeletonkey.plugin;

import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Input of a plugin handler: a read-only copy of the record data, the instance state, the caller and the action code
 * (null when the handler runs from an event or AUTO step).
 */
public record HandlerContext(Map<String, Object> data, InstanceState instance, Principal principal,
                             String actionCode) {
    public HandlerContext {
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
