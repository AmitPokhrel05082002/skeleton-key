package dev.skeletonkey.domain.expr;

import java.util.List;

/**
 * Result of walking an expression. All pointers are JSON Pointers (RFC 6901) relative to the
 * expression root; the caller prefixes the location of the expression inside the definition.
 *
 * @param operators  every operator occurrence, supported or not, in document order
 * @param varRefs    every {@code var} reference with a string path, in document order
 * @param parseErrors structural problems (bad object shape, non-string var path, depth cap)
 */
public record ExprAnalysis(List<OperatorUse> operators, List<VarRef> varRefs, List<ParseError> parseErrors) {

  public ExprAnalysis {
    operators = List.copyOf(operators);
    varRefs = List.copyOf(varRefs);
    parseErrors = List.copyOf(parseErrors);
  }

  /** One occurrence of an operator. */
  public record OperatorUse(String name, String pointer) {
    public boolean supported() {
      return ExpressionEngine.OPERATORS.contains(name);
    }
  }

  /** One {@code var} reference. {@code path} is the dotted path, e.g. {@code data.category}. */
  public record VarRef(String path, String pointer) {

    /** First dotted segment of the path (empty string for an empty path). */
    public String root() {
      int dot = path.indexOf('.');
      return dot < 0 ? path : path.substring(0, dot);
    }

    public boolean validRoot() {
      return ExpressionEngine.VAR_ROOTS.contains(root());
    }

    /** For {@code data.x[.y]} the field code {@code x}; otherwise null. */
    public String dataField() {
      if (!"data".equals(root()) || path.length() <= 5) {
        return null;
      }
      String rest = path.substring(5);
      int dot = rest.indexOf('.');
      String field = dot < 0 ? rest : rest.substring(0, dot);
      return field.isEmpty() ? null : field;
    }
  }

  public record ParseError(String pointer, String message) {}

  /** Operator occurrences outside the allow-list. */
  public List<OperatorUse> unsupportedOperators() {
    return operators.stream().filter(o -> !o.supported()).toList();
  }

  /** Var references whose first segment is not data, instance or user. */
  public List<VarRef> invalidRootVars() {
    return varRefs.stream().filter(v -> !v.validRoot()).toList();
  }

  /** True when the expression may be handed to the evaluation library. */
  public boolean evaluable() {
    return parseErrors.isEmpty() && unsupportedOperators().isEmpty() && invalidRootVars().isEmpty();
  }
}
