package dev.skeletonkey.domain.validator;

import java.util.Comparator;

/**
 * One publish-time rule violation.
 *
 * @param rule    stable rule code, e.g. {@code DUPLICATE_CODE}
 * @param path    location of the offending element, e.g. {@code entities[TICKET].fields[title]}
 * @param message human-readable explanation naming the offending values
 */
public record Violation(String rule, String path, String message) {

    /** Deterministic ordering used by {@link ValidationReport}: path, then rule, then message. */
    public static final Comparator<Violation> ORDER = Comparator
            .comparing(Violation::path)
            .thenComparing(Violation::rule)
            .thenComparing(Violation::message);
}
