package dev.skeletonkey.web;

import java.util.List;

/** 422 VALIDATION_FAILED with per-field violations. */
public class ValidationFailed extends RuntimeException {
    private final List<ApiViolation> violations;

    public ValidationFailed(String message, List<ApiViolation> violations) {
        super(message);
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public List<ApiViolation> violations() { return violations; }
}