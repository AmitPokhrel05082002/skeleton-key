package dev.skeletonkey.domain.load;

/** The document is not well-formed JSON (HTTP 400). Line and column are 1-based and mark the first error. */
public class DefinitionSyntaxException extends RuntimeException {
    private final int line;
    private final int column;

    public DefinitionSyntaxException(String message, int line, int column) {
        super(message + " (line " + line + ", column " + column + ")");
        this.line = line;
        this.column = column;
    }

    public int line() { return line; }

    public int column() { return column; }
}
