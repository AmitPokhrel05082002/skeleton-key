package dev.skeletonkey.domain.validator;

import dev.skeletonkey.domain.model.Action;
import dev.skeletonkey.domain.model.AppDefinition;
import dev.skeletonkey.domain.model.Assignee;
import dev.skeletonkey.domain.model.Entity;
import dev.skeletonkey.domain.model.Field;
import dev.skeletonkey.domain.model.Status;
import dev.skeletonkey.domain.model.Step;
import dev.skeletonkey.domain.model.Transition;
import dev.skeletonkey.domain.model.Workflow;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Req 3.1 to 3.16: workflow graph soundness. Expression field references (skipWhen, when) are
 * checked by the expression rule group (EXPR_FIELD), not here.
 */
final class WorkflowGraphRules implements RuleGroup {

    static final String BAD_START_STEP = "BAD_START_STEP";
    static final String DUPLICATE_STEP = "DUPLICATE_STEP";
    static final String NO_END_STEP = "NO_END_STEP";
    static final String UNREACHABLE_STEP = "UNREACHABLE_STEP";
    static final String DANGLING_TRANSITION = "DANGLING_TRANSITION";
    static final String BAD_TRANSITION_ACTION = "BAD_TRANSITION_ACTION";
    static final String UNKNOWN_STATUS = "UNKNOWN_STATUS";
    static final String UNSET_STATUS = "UNSET_STATUS";
    static final String DANGLING_ASSIGNEE = "DANGLING_ASSIGNEE";
    static final String DANGLING_STEP_FIELD = "DANGLING_STEP_FIELD";
    static final String DEAD_END = "DEAD_END";
    static final String DANGLING_ACTION_ROLE = "DANGLING_ACTION_ROLE";
    static final String AMBIGUOUS_PRIORITY = "AMBIGUOUS_PRIORITY";
    static final String DANGLING_BRANCH = "DANGLING_BRANCH";
    static final String JOIN_MISMATCH = "JOIN_MISMATCH";
    static final String UNSUPPORTED_STEP = "UNSUPPORTED_STEP";

    @Override
    public void check(AppDefinition def, Consumer<Violation> out) {
        Set<String> roleCodes = new HashSet<>();
        def.roles().forEach(r -> roleCodes.add(r.code()));
        for (Workflow wf : def.workflows()) {
            new Run(def, wf, roleCodes, out).run();
        }
    }

    private static final class Run {
        private final AppDefinition def;
        private final Workflow wf;
        private final Set<String> roleCodes;
        private final Consumer<Violation> out;
        private final String wfPath;
        private final Set<String> stepCodes = new HashSet<>();
        private final Set<String> statusLabels = new HashSet<>();

        Run(AppDefinition def, Workflow wf, Set<String> roleCodes, Consumer<Violation> out) {
            this.def = def;
            this.wf = wf;
            this.roleCodes = roleCodes;
            this.out = out;
            this.wfPath = "workflows[" + wf.code() + "]";
            wf.steps().forEach(s -> stepCodes.add(s.code()));
            wf.statuses().forEach(s -> statusLabels.add(s.label()));
        }

        void run() {
            startAndDuplicates();
            transitions();
            statuses();
            for (Step step : wf.steps()) {
                stepRules(step);
            }
            joins();
            reachability();
            deadEnds();
            unsetStatuses();
        }

        private String stepPath(Step s) {
            return wfPath + ".steps[" + s.code() + "]";
        }

        private void report(String rule, String path, String message) {
            out.accept(new Violation(rule, path, message));
        }

        /** Req 3.1, 3.2, 3.3. */
        private void startAndDuplicates() {
            if (!stepCodes.contains(wf.startStep())) {
                report(BAD_START_STEP, wfPath, "Workflow " + wf.code() + " startStep " + wf.startStep()
                        + " is not a step of the workflow");
            }
            Set<String> seen = new HashSet<>();
            Set<String> dup = new LinkedHashSet<>();
            for (Step s : wf.steps()) {
                if (!seen.add(s.code())) {
                    dup.add(s.code());
                }
            }
            for (String code : dup) {
                report(DUPLICATE_STEP, wfPath + ".steps[" + code + "]",
                        "Workflow " + wf.code() + " has duplicate step code " + code);
            }
            if (wf.steps().stream().noneMatch(s -> s.type() == Step.Type.END)) {
                report(NO_END_STEP, wfPath, "Workflow " + wf.code() + " has no END step");
            }
        }

        /** Req 3.5, 3.6, 3.12 plus transition status (3.7). */
        private void transitions() {
            Map<String, List<Transition>> groups = new LinkedHashMap<>();
            int i = 0;
            for (Transition t : wf.transitions()) {
                String path = wfPath + ".transitions[" + i++ + "]";
                if (!stepCodes.contains(t.from())) {
                    report(DANGLING_TRANSITION, path, "Transition " + t.from() + " -> " + t.to()
                            + " in workflow " + wf.code() + " names missing step " + t.from() + " (from)");
                }
                if (!stepCodes.contains(t.to())) {
                    report(DANGLING_TRANSITION, path, "Transition " + t.from() + " -> " + t.to()
                            + " in workflow " + wf.code() + " names missing step " + t.to() + " (to)");
                }
                if (t.on() != null && !t.on().isEmpty()) {
                    Optional<Step> from = wf.step(t.from());
                    if (from.isPresent() && from.get().action(t.on()).isEmpty()) {
                        report(BAD_TRANSITION_ACTION, path, "Transition from step " + t.from()
                                + " in workflow " + wf.code() + " names missing action " + t.on());
                    }
                }
                checkStatus(t.status(), path, "Transition " + t.from() + " -> " + t.to());
                groups.computeIfAbsent(t.from() + "\u0000" + (t.on() == null ? "" : t.on()) + "\u0000"
                        + t.priority(), k -> new java.util.ArrayList<>()).add(t);
            }
            for (List<Transition> g : groups.values()) {
                if (g.size() > 1) {
                    Transition t = g.get(0);
                    report(AMBIGUOUS_PRIORITY, wfPath + ".transitions[" + t.from() + "]",
                            "Workflow " + wf.code() + " has " + g.size() + " transitions from step " + t.from()
                                    + " on action '" + (t.on() == null ? "" : t.on())
                                    + "' sharing priority " + t.priority());
                }
            }
        }

        private void checkStatus(String label, String path, String owner) {
            if (label != null && !statusLabels.contains(label)) {
                report(UNKNOWN_STATUS, path, owner + " in workflow " + wf.code()
                        + " names status label '" + label + "' which is not declared");
            }
        }

        /** Req 3.7 for initialStatus. */
        private void statuses() {
            checkStatus(wf.initialStatus(), wfPath, "initialStatus");
        }

        private void stepRules(Step step) {
            String path = stepPath(step);
            checkStatus(step.status(), path, "Step " + step.code());
            if (step.type() == Step.Type.END) {
                checkStatus(step.outcome(), path, "END step " + step.code() + " outcome");
            }
            if (step.type() == Step.Type.PARALLEL || step.type() == Step.Type.JOIN
                    || step.type() == Step.Type.SUBWORKFLOW) {
                report(UNSUPPORTED_STEP, path, "Step " + step.code() + " in workflow " + wf.code()
                        + " has type " + step.type() + " which the Router does not execute");
            }
            if (step.type() == Step.Type.PARALLEL) {
                for (String b : step.branches()) {
                    if (!stepCodes.contains(b)) {
                        report(DANGLING_BRANCH, path, "PARALLEL step " + step.code() + " in workflow "
                                + wf.code() + " names missing branch step " + b);
                    }
                }
                if (step.join() != null && !stepCodes.contains(step.join())) {
                    report(DANGLING_BRANCH, path, "PARALLEL step " + step.code() + " in workflow "
                            + wf.code() + " names missing join step " + step.join());
                }
            }
            assignee(step, path);
            Optional<Entity> entity = def.entity(wf.entity());
            for (String field : step.fields().keySet()) {
                if (entity.isPresent() && entity.get().field(field).isEmpty()) {
                    report(DANGLING_STEP_FIELD, path, "Step " + step.code() + " in workflow " + wf.code()
                            + " fields names missing field " + field);
                }
            }
            for (String field : step.copyFields()) {
                if (entity.isPresent() && entity.get().field(field).isEmpty()) {
                    report(DANGLING_STEP_FIELD, path, "Step " + step.code() + " in workflow " + wf.code()
                            + " copyFields names missing field " + field);
                }
            }
            for (Action a : step.actions()) {
                String aPath = path + ".actions[" + a.code() + "]";
                if (a.to() != null && !stepCodes.contains(a.to())) {
                    report(DANGLING_TRANSITION, aPath, "Action " + a.code() + " of step " + step.code()
                            + " in workflow " + wf.code() + " targets missing step " + a.to());
                }
                checkStatus(a.status(), aPath, "Action " + a.code() + " of step " + step.code());
                for (String role : a.roles()) {
                    if (!roleCodes.contains(role)) {
                        report(DANGLING_ACTION_ROLE, aPath, "Action " + a.code() + " of step " + step.code()
                                + " in workflow " + wf.code() + " names missing role " + role);
                    }
                }
            }
        }

        private void assignee(Step step, String path) {
            Assignee a = step.assignee();
            if (a == null) {
                return;
            }
            if (a.role() != null && !roleCodes.contains(a.role())) {
                report(DANGLING_ASSIGNEE, path, "Step " + step.code() + " in workflow " + wf.code()
                        + " assignee names missing role " + a.role());
            }
            if (a.field() != null) {
                Optional<Entity> entity = def.entity(wf.entity());
                if (entity.isPresent()) {
                    Optional<Field> f = entity.get().field(a.field());
                    if (f.isEmpty() || f.get().type() != Field.Type.USER) {
                        report(DANGLING_ASSIGNEE, path, "Step " + step.code() + " in workflow " + wf.code()
                                + " assignee field " + a.field() + " is not a USER field of entity "
                                + wf.entity());
                    }
                }
            }
        }

        /** Req 3.14. */
        private void joins() {
            Map<String, Integer> naming = new HashMap<>();
            for (Step s : wf.steps()) {
                if (s.type() == Step.Type.PARALLEL && s.join() != null) {
                    naming.merge(s.join(), 1, Integer::sum);
                }
            }
            Set<String> reported = new HashSet<>();
            for (Step s : wf.steps()) {
                if (s.type() == Step.Type.JOIN && reported.add(s.code())) {
                    int n = naming.getOrDefault(s.code(), 0);
                    if (n != 1) {
                        report(JOIN_MISMATCH, stepPath(s), "JOIN step " + s.code() + " in workflow "
                                + wf.code() + " is named by " + n + " PARALLEL steps, expected exactly 1");
                    }
                }
            }
        }

        /** Successor step codes of a step, over-approximating routing (Req 3.4). */
        private Set<String> successors(int index) {
            Step step = wf.steps().get(index);
            Set<String> next = new LinkedHashSet<>();
            if (step.type() == Step.Type.END) {
                return next;
            }
            for (Action a : step.actions()) {
                if (a.to() != null) {
                    next.add(a.to());
                }
            }
            for (Transition t : wf.transitions()) {
                if (t.from().equals(step.code())) {
                    next.add(t.to());
                }
            }
            next.addAll(step.branches());
            if (step.join() != null) {
                next.add(step.join());
            }
            if (index + 1 < wf.steps().size()) {
                next.add(wf.steps().get(index + 1).code());
            }
            next.retainAll(stepCodes);
            return next;
        }

        private void reachability() {
            if (!stepCodes.contains(wf.startStep())) {
                return;
            }
            Map<String, Integer> firstIndex = new HashMap<>();
            for (int i = 0; i < wf.steps().size(); i++) {
                firstIndex.putIfAbsent(wf.steps().get(i).code(), i);
            }
            Set<String> seen = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            seen.add(wf.startStep());
            queue.add(wf.startStep());
            while (!queue.isEmpty()) {
                String code = queue.poll();
                for (String n : successors(firstIndex.get(code))) {
                    if (seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
            Set<String> reported = new HashSet<>();
            for (Step s : wf.steps()) {
                if (!seen.contains(s.code()) && reported.add(s.code())) {
                    report(UNREACHABLE_STEP, stepPath(s), "Step " + s.code() + " in workflow " + wf.code()
                            + " is not reachable from startStep " + wf.startStep());
                }
            }
        }

        /** Req 3.10. */
        private void deadEnds() {
            List<Step> steps = wf.steps();
            for (int i = 0; i < steps.size(); i++) {
                Step s = steps.get(i);
                if (s.type() != Step.Type.TASK && s.type() != Step.Type.APPROVAL) {
                    continue;
                }
                boolean hasFollowing = i + 1 < steps.size();
                boolean resolvable = false;
                for (Action a : s.actions()) {
                    if (actionResolves(s, a, hasFollowing)) {
                        resolvable = true;
                        break;
                    }
                }
                if (!resolvable) {
                    report(DEAD_END, stepPath(s), "Step " + s.code() + " in workflow " + wf.code()
                            + " has no action that leads to another step");
                }
            }
        }

        private boolean actionResolves(Step s, Action a, boolean hasFollowing) {
            if (a.to() != null && stepCodes.contains(a.to())) {
                return true;
            }
            for (Transition t : wf.transitions()) {
                boolean applies = t.from().equals(s.code())
                        && (t.on() == null || t.on().isEmpty() || t.on().equals(a.code()));
                if (applies && stepCodes.contains(t.to())) {
                    return true;
                }
            }
            return hasFollowing;
        }

        /** Req 3.16. */
        private void unsetStatuses() {
            Set<String> settable = new HashSet<>();
            if (wf.initialStatus() != null) {
                settable.add(wf.initialStatus());
            } else {
                for (Status st : wf.statuses()) {
                    if (st.category() == Status.Category.OPEN) {
                        settable.add(st.label());
                        break;
                    }
                }
            }
            wf.transitions().forEach(t -> addIfSet(settable, t.status()));
            for (Step s : wf.steps()) {
                if (s.type() == Step.Type.END) {
                    addIfSet(settable, s.outcome());
                } else {
                    addIfSet(settable, s.status());
                }
                s.actions().forEach(a -> addIfSet(settable, a.status()));
            }
            Set<String> reported = new HashSet<>();
            for (Status st : wf.statuses()) {
                if (!settable.contains(st.label()) && reported.add(st.label())) {
                    report(UNSET_STATUS, wfPath + ".statuses[" + st.label() + "]", "Status '" + st.label()
                            + "' in workflow " + wf.code() + " cannot be set by any action, transition, step,"
                            + " initialStatus or END outcome");
                }
            }
        }

        private static void addIfSet(Set<String> set, String label) {
            if (label != null) {
                set.add(label);
            }
        }
    }
}
