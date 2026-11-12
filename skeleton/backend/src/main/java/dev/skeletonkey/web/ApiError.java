package dev.skeletonkey.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * JSON error shape returned for every failed request (Req 20.6). line and column are only set for malformed JSON
 * definitions (1-based).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String code, String message, String requestId, List<ApiViolation> violations,
                       Integer line, Integer column) {
    public ApiError(int status, String code, String message, String requestId, List<ApiViolation> violations) {
        this(status, code, message, requestId, violations, null, null);
    }
}
