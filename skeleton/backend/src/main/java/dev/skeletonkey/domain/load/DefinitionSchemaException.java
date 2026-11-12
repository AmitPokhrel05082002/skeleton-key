package dev.skeletonkey.domain.load;

import java.util.List;

/** The document violates the JSON Schema (HTTP 422). Lists every violation. */
public class DefinitionSchemaException extends RuntimeException {
    private final List<SchemaViolation> violations;

    public DefinitionSchemaException(List<SchemaViolation> violations) {
        super("Definition violates the schema: " + violations.size() + " violation(s)");
        this.violations = List.copyOf(violations);
    }

    public List<SchemaViolation> violations() { return violations; }
}
