package dev.skeletonkey.domain.expr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skeletonkey.domain.expr.ExprAnalysis.OperatorUse;
import dev.skeletonkey.domain.expr.ExprAnalysis.ParseError;
import dev.skeletonkey.domain.expr.ExprAnalysis.VarRef;
import io.github.jamsesso.jsonlogic.JsonLogic;
import io.github.jamsesso.jsonlogic.JsonLogicException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * JSONLogic analysis and evaluation. Analysis is an in-house walker (the library does not expose
 * operators, var paths or locations). Evaluation is delegated to json-logic-java, but only after
 * the same allow-list check, so library-specific extra operators are unreachable.
 */
@Component
public class ExpressionEngine {

  /** Fixed operator allow-list. */
  public static final Set<String> OPERATORS = Set.of(
      "==", "!=", "===", "!==", "!", "!!", "and", "or", "if", ">", ">=", "<", "<=",
      "+", "-", "*", "/", "%", "min", "max", "in", "var", "cat", "missing", "missing_some", "merge");

  /** Allowed first segment of a {@code var} path. */
  public static final Set<String> VAR_ROOTS = Set.of("data", "instance", "user");

  /** Maximum nesting depth of an expression tree; deeper trees yield a parse error. */
  public static final int MAX_DEPTH = 64;

  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * Walks the expression. Total: never throws for any JsonNode, including null.
   * An object with exactly one key is an operation; any other object shape is a parse error.
   * Arrays and scalars are walked / accepted.
   */
  public ExprAnalysis analyze(JsonNode expr) {
    List<OperatorUse> ops = new ArrayList<>();
    List<VarRef> vars = new ArrayList<>();
    List<ParseError> errors = new ArrayList<>();
    walk(expr, "", 0, ops, vars, errors);
    return new ExprAnalysis(ops, vars, errors);
  }

  /** Evaluates with JSONLogic semantics; the result is a plain Java value (may be null). */
  public Object evaluate(JsonNode expr, EvalContext ctx) {
    ExprAnalysis analysis = analyze(expr);
    if (!analysis.parseErrors().isEmpty()) {
      ParseError e = analysis.parseErrors().get(0);
      throw new ExpressionException("Malformed expression at '" + e.pointer() + "': " + e.message());
    }
    if (!analysis.unsupportedOperators().isEmpty()) {
      OperatorUse o = analysis.unsupportedOperators().get(0);
      throw new ExpressionException("Unsupported operator '" + o.name() + "' at '" + o.pointer() + "'");
    }
    if (!analysis.invalidRootVars().isEmpty()) {
      VarRef v = analysis.invalidRootVars().get(0);
      throw new ExpressionException("Invalid var root '" + v.root() + "' at '" + v.pointer() + "'");
    }
    try {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("data", toJava(ctx == null ? null : ctx.data()));
      data.put("instance", toJava(ctx == null ? null : ctx.instance()));
      data.put("user", toJava(ctx == null ? null : ctx.user()));
      return new JsonLogic().apply(mapper.writeValueAsString(expr), data);
    } catch (JsonLogicException e) {
      throw new ExpressionException("Expression evaluation failed: " + e.getMessage(), e);
    } catch (RuntimeException | StackOverflowError | com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ExpressionException("Expression evaluation failed: " + e, e);
    }
  }

  /** Evaluates and requires a Boolean result (event {@code when}, Req 14.4). */
  public boolean evaluateBoolean(JsonNode expr, EvalContext ctx) {
    Object result = evaluate(expr, ctx);
    if (result instanceof Boolean b) {
      return b;
    }
    throw new ExpressionException(
        "Expression must evaluate to a boolean but produced "
            + (result == null ? "null" : result.getClass().getSimpleName()));
  }

  /** JSONLogic truthiness as implemented by the library. */
  public boolean truthy(Object value) {
    return JsonLogic.truthy(value);
  }

  // ---- internals ----

  private Object toJava(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    return mapper.convertValue(node, Object.class);
  }

  private void walk(JsonNode node, String pointer, int depth,
                    List<OperatorUse> ops, List<VarRef> vars, List<ParseError> errors) {
    if (node == null || node.isMissingNode()) {
      errors.add(new ParseError(pointer, "Missing expression"));
      return;
    }
    if (depth > MAX_DEPTH) {
      errors.add(new ParseError(pointer, "Expression nesting exceeds depth " + MAX_DEPTH));
      return;
    }
    if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        walk(node.get(i), pointer + "/" + i, depth + 1, ops, vars, errors);
      }
      return;
    }
    if (!node.isObject()) {
      return; // scalar literal or null
    }
    if (node.size() != 1) {
      errors.add(new ParseError(pointer,
          "An operation must be an object with exactly one key, found " + node.size()));
      return;
    }
    Iterator<Map.Entry<String, JsonNode>> it = node.fields();
    Map.Entry<String, JsonNode> entry = it.next();
    String op = entry.getKey();
    JsonNode args = entry.getValue();
    String opPointer = pointer + "/" + escape(op);
    ops.add(new OperatorUse(op, pointer));

    if ("var".equals(op)) {
      walkVar(args, opPointer, depth, ops, vars, errors);
      return;
    }
    if (args.isArray()) {
      for (int i = 0; i < args.size(); i++) {
        walk(args.get(i), opPointer + "/" + i, depth + 1, ops, vars, errors);
      }
    } else {
      walk(args, opPointer, depth + 1, ops, vars, errors);
    }
  }

  private void walkVar(JsonNode args, String opPointer, int depth,
                       List<OperatorUse> ops, List<VarRef> vars, List<ParseError> errors) {
    JsonNode pathNode;
    JsonNode defaultNode = null;
    String pathPointer;
    if (args.isArray()) {
      if (args.isEmpty() || args.size() > 2) {
        errors.add(new ParseError(opPointer, "var takes a path and an optional default"));
        return;
      }
      pathNode = args.get(0);
      pathPointer = opPointer + "/0";
      if (args.size() == 2) {
        defaultNode = args.get(1);
      }
    } else {
      pathNode = args;
      pathPointer = opPointer;
    }
    if (pathNode.isTextual()) {
      vars.add(new VarRef(pathNode.asText(), pathPointer));
    } else {
      errors.add(new ParseError(pathPointer, "var path must be a string"));
    }
    if (defaultNode != null) {
      walk(defaultNode, opPointer + "/1", depth + 1, ops, vars, errors);
    }
  }

  private static String escape(String token) {
    return token.replace("~", "~0").replace("/", "~1");
  }
}
