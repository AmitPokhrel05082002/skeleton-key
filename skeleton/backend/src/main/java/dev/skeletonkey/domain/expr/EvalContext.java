package dev.skeletonkey.domain.expr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * The three roots an expression may read: {@code data}, {@code instance} and {@code user}.
 * A null root is treated as an empty (null) value.
 */
public record EvalContext(JsonNode data, JsonNode instance, JsonNode user) {

  public EvalContext {
    data = data == null ? MissingNode.getInstance() : data;
    instance = instance == null ? MissingNode.getInstance() : instance;
    user = user == null ? MissingNode.getInstance() : user;
  }
}
