package dev.skeletonkey.domain.validator;

import java.util.List;

/** Result of validating a definition. Violations are sorted by (path, rule, message). */
public record ValidationReport(List<Violation> violations) {

    public ValidationReport {
        violations = violations.stream().sorted(Violation.ORDER).toList();
    }

    public boolean ok() {
        return violations.isEmpty();
    }
}
