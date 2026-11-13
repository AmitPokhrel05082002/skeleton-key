package dev.skeletonkey.app;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Instance_View (Req 15): where the instance is, what the caller may do now and the effective field rule of every
 * field of the workflow entity (readonly, optional, required or hidden).
 */
public record InstanceView(UUID instanceId, UUID recordId, String step, String status, boolean ended, Instant dueAt,
                           UUID assignee, List<AvailableAction> actions, Map<String, String> fieldRules) {

    /** An action the caller can run on the current step. */
    public record AvailableAction(String code, String label, String style, String confirm, String remarks,
                                  String attachment) {}
}
