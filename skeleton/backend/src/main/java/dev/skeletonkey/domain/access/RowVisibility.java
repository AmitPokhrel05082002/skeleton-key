package dev.skeletonkey.domain.access;

import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.InstanceState;
import dev.skeletonkey.domain.model.Principal;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.View;
import dev.skeletonkey.domain.model.Workflow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Row visibility (Req 7.9, 15.5), implemented once. A record or workflow instance is visible to a principal only if
 * the principal
 * <ol>
 *   <li>(a) holds a role of a view of the entity that grants the whole entity (a view without {@code roles} is
 *       available to any authenticated user, as for the definition endpoint; only views with scope ALL grant
 *       rows beyond clauses b-d, and MY_TASKS views are per-user queues so they grant nothing by themselves),</li>
 *   <li>(b) created it,</li>
 *   <li>(c) is its current assignee, or</li>
 *   <li>(d) holds the role of the current step's assignee.</li>
 * </ol>
 * Anonymous callers (no user id) see nothing. The same rule is exposed as an in-memory predicate and as a SQL
 * fragment with bound parameters, so list queries filter in the database and paging totals stay correct.
 * Escalation roles are not stored on the instance, so only the step's {@code assignee.role} is considered.
 */
public final class RowVisibility {

    /** A workflow step identified by workflow and step code. */
    public record StepKey(String workflow, String step) {}

    /** SQL text plus its named parameters. */
    public record Sql(String text, Map<String, Object> params) {}

    private final UUID userId;
    private final boolean viewGrant;
    private final Set<StepKey> roleSteps;

    private RowVisibility(UUID userId, boolean viewGrant, Set<StepKey> roleSteps) {
        this.userId = userId;
        this.viewGrant = viewGrant;
        this.roleSteps = Set.copyOf(roleSteps);
    }

    public static RowVisibility of(Principal principal, boolean viewGrant, Set<StepKey> roleSteps) {
        return new RowVisibility(principal == null ? null : principal.userId(), viewGrant, roleSteps);
    }

    /** Derives clauses (a) and (d) for {@code entityCode} from the definition. */
    public static RowVisibility of(AppDefinition def, String entityCode, Principal principal) {
        boolean grant = principal != null && principal.userId() != null && def.views().stream()
                .filter(v -> v.entity().equals(entityCode))
                .filter(v -> v.type() != View.Type.MY_TASKS)
                .filter(v -> v.scope() == null || v.scope() == View.Scope.ALL)
                .anyMatch(v -> v.roles().isEmpty() || principal.hasAnyRole(v.roles()));
        return of(principal, grant, roleSteps(def, entityCode, principal));
    }

    /** Steps of workflows on the entity whose assignee role is held by the principal. */
    public static Set<StepKey> roleSteps(AppDefinition def, String entityCode, Principal principal) {
        Set<StepKey> out = new LinkedHashSet<>();
        if (principal == null) {
            return out;
        }
        for (Workflow w : def.workflows()) {
            if (!w.entity().equals(entityCode)) {
                continue;
            }
            for (Step s : w.steps()) {
                if (s.assignee() != null && s.assignee().role() != null
                        && principal.roles().contains(s.assignee().role())) {
                    out.add(new StepKey(w.code(), s.code()));
                }
            }
        }
        return out;
    }

    // ---- in-memory predicate -------------------------------------------------------------------------------

    /** @param instance the workflow instance of the record, or null when it has none */
    public boolean isVisible(UUID createdBy, InstanceState instance) {
        if (userId == null) {
            return false;
        }
        if (viewGrant || userId.equals(createdBy)) {
            return true;
        }
        if (instance == null) {
            return false;
        }
        return userId.equals(instance.assigneeId()) || inRoleStep(instance);
    }

    /** True when the instance's current step is assigned to a role the principal holds. */
    public boolean inRoleStep(InstanceState instance) {
        return instance != null && roleSteps.contains(new StepKey(instance.workflowCode(), instance.step()));
    }

    /** MY_ROLE_QUEUE: open, unassigned, waiting at a step of the principal's role. */
    public boolean inRoleQueue(InstanceState instance) {
        return userId != null && instance != null && !instance.ended() && instance.assigneeId() == null
                && inRoleStep(instance);
    }

    public UUID userId() {
        return userId;
    }

    // ---- SQL ---------------------------------------------------------------------------------------------

    /**
     * Visibility as a boolean SQL expression. {@code recordAlias} must expose {@code created_by}; {@code
     * instanceAlias} (LEFT JOINed) must expose {@code assignee}, {@code workflow_code} and {@code current_step}.
     * Parameter names start with {@code prefix}.
     */
    public Sql sql(String recordAlias, String instanceAlias, String prefix) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userId == null) {
            return new Sql("(1 = 0)", params);
        }
        if (viewGrant) {
            return new Sql("(1 = 1)", params);
        }
        params.put(prefix + "User", userId);
        List<String> clauses = new ArrayList<>();
        clauses.add(recordAlias + ".created_by = :" + prefix + "User");
        clauses.add(instanceAlias + ".assignee = :" + prefix + "User");
        String steps = stepsSql(instanceAlias, prefix, params);
        if (steps != null) {
            clauses.add(steps);
        }
        return new Sql("(" + String.join(" OR ", clauses) + ")", params);
    }

    /** MY_ROLE_QUEUE scope as SQL (same alias rules as {@link #sql}); also needs {@code ended}. */
    public Sql queueSql(String instanceAlias, String prefix) {
        Map<String, Object> params = new LinkedHashMap<>();
        String steps = userId == null ? null : stepsSql(instanceAlias, prefix, params);
        if (steps == null) {
            return new Sql("(1 = 0)", params);
        }
        return new Sql("(" + instanceAlias + ".ended = false AND " + instanceAlias + ".assignee IS NULL AND "
                + steps + ")", params);
    }

    private String stepsSql(String wi, String prefix, Map<String, Object> params) {
        if (roleSteps.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        int i = 0;
        for (StepKey k : roleSteps) {
            params.put(prefix + "W" + i, k.workflow());
            params.put(prefix + "S" + i, k.step());
            parts.add("(" + wi + ".workflow_code = :" + prefix + "W" + i + " AND " + wi + ".current_step = :"
                    + prefix + "S" + i + ")");
            i++;
        }
        return "(" + String.join(" OR ", parts) + ")";
    }
}
