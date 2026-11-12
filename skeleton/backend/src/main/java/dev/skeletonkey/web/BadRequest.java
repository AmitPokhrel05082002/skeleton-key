package dev.skeletonkey.web;

import java.util.List;

/** 400 BAD_REQUEST with per-field violations (e.g. login input validation). */
public class BadRequest extends RuntimeException {
    private final List<ApiViolation> violations;

    public BadRequest(String message, List<ApiViolation> violations) {
        super(message);
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public List<ApiViolation> violations() { return violations; }
}
