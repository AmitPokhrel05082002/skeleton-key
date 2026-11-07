package dev.skeletonkey.domain.route;

import dev.skeletonkey.web.ApiViolation;
import java.util.List;

/** Thrown by the routing pipeline; carries HTTP status, a stable code and optional per-field details. */
public class RouterException extends RuntimeException {
    private final int status;
    private final String code;
    private final List<ApiViolation> details;

    public RouterException(int status, String code, String message, List<ApiViolation> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public RouterException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public int status() { return status; }
    public String code() { return code; }
    public List<ApiViolation> details() { return details; }
}