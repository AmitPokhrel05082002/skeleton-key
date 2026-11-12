package dev.skeletonkey.domain.model;

import java.util.List;
import java.util.Optional;

/** A workflow inside a versioned AppDefinition. It has no version of its own. */
public record Workflow(String code, String label, String entity, String startStep, String initialStatus,
                       List<Status> statuses, List<Step> steps, List<Transition> transitions) {
    public Workflow {
        statuses = List.copyOf(statuses);
        steps = List.copyOf(steps);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
    }

    public Optional<Step> step(String stepCode) {
        return steps.stream().filter(s -> s.code().equals(stepCode)).findFirst();
    }
}
