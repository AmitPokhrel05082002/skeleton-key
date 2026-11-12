package dev.skeletonkey.record;

/**
 * One problem found while validating a record payload.
 *
 * @param field      field code (or the offending unknown key)
 * @param constraint machine-readable constraint name, e.g. REQUIRED, UNKNOWN_FIELD, TYPE, MAX_LENGTH
 * @param message    human-readable explanation
 */
public record FieldViolation(String field, String constraint, String message) {}
