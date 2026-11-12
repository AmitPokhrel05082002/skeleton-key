package dev.skeletonkey.domain.expr;

/**
 * Raised when an expression cannot be evaluated: it uses an operator outside the allow-list, is
 * structurally malformed, the library fails (type error, divide by zero, ...) or an event
 * {@code when} does not produce a boolean. Callers decide the policy (for example, a transition
 * with a failing {@code when} simply does not match).
 */
public class ExpressionException extends RuntimeException {

  public ExpressionException(String message) {
    super(message);
  }

  public ExpressionException(String message, Throwable cause) {
    super(message, cause);
  }
}
