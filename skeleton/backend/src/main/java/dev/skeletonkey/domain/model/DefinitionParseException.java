package dev.skeletonkey.domain.model;

/** Thrown when a JSON document cannot be mapped to the model (it is not shaped like the schema). */
public class DefinitionParseException extends RuntimeException {
    public DefinitionParseException(String message) { super(message); }
}
