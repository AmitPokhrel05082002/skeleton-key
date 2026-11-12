package dev.skeletonkey.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One entry of the error body's {@code violations} array. {@code field} holds the field name, or the JSON Pointer
 * path for definition violations; {@code message} is optional.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiViolation(String field, String rule, String message) {
    public ApiViolation(String field, String rule) {
        this(field, rule, null);
    }
}
