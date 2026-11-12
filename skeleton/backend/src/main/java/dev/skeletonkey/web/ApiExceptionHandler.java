package dev.skeletonkey.web;

import dev.skeletonkey.domain.load.DefinitionSchemaException;
import dev.skeletonkey.domain.load.DefinitionSyntaxException;
import dev.skeletonkey.domain.route.RouterException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps exceptions to the common JSON error body (Req 20.6). */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RouterException.class)
    ResponseEntity<ApiError> router(RouterException e) {
        if (e.status() >= 500) {
            // Never expose internal detail (e.g. a plugin handler's exception text); the request id identifies the log entry.
            log.error("Routing failure {}", e.code(), e);
            return build(e.status(), e.code(), "Internal server error", List.of());
        }
        return build(e.status(), e.code(), e.getMessage(), e.details());
    }

    @ExceptionHandler(ValidationFailed.class)
    ResponseEntity<ApiError> validation(ValidationFailed e) {
        return build(422, "VALIDATION_FAILED", e.getMessage(), e.violations());
    }

    @ExceptionHandler(DefinitionSyntaxException.class)
    ResponseEntity<ApiError> malformedJson(DefinitionSyntaxException e) {
        ApiError body = new ApiError(400, "MALFORMED_JSON", e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY),
                List.of(), e.line(), e.column());
        return ResponseEntity.status(400).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
    }

    @ExceptionHandler(DefinitionSchemaException.class)
    ResponseEntity<ApiError> schemaViolations(DefinitionSchemaException e) {
        List<ApiViolation> violations = e.violations().stream()
                .map(v -> new ApiViolation(v.path(), v.rule(), v.message()))
                .toList();
        return build(422, "VALIDATION_FAILED", e.getMessage(), violations);
    }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<ApiError> notFound(NotFound e) {
        return build(404, "NOT_FOUND", e.getMessage(), List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noResource(NoResourceFoundException e) {
        return build(404, "NOT_FOUND", "Resource not found", List.of());
    }

    @ExceptionHandler(Conflict.class)
    ResponseEntity<ApiError> conflict(Conflict e) {
        return build(409, e.code(), e.getMessage(), List.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> optimistic(OptimisticLockingFailureException e) {
        return build(409, "CONCURRENT_MODIFICATION", "The resource was modified concurrently; retry", List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(AccessDeniedException e) {
        return build(403, "FORBIDDEN", "Access denied", List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> unauthenticated(AuthenticationException e) {
        return build(401, "UNAUTHENTICATED", "Authentication required", List.of());
    }

    /** History is append-only: PUT/PATCH/DELETE (and any unsupported method) is 405 NOT_SUPPORTED (Req 12.7). */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotSupported(HttpRequestMethodNotSupportedException e) {
        ResponseEntity<ApiError> base = build(405, "NOT_SUPPORTED", "Method " + e.getMethod() + " is not supported", List.of());
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(base.getHeaders());
        if (e.getSupportedHttpMethods() != null) {
            headers.setAllow(e.getSupportedHttpMethods());
        }
        return new ResponseEntity<>(base.getBody(), headers, HttpStatus.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return build(400, "BAD_REQUEST", "Malformed request body", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return build(500, "INTERNAL_ERROR", "Internal server error", List.of());
    }

    private ResponseEntity<ApiError> build(int status, String code, String message, List<ApiViolation> violations) {
        ApiError body = new ApiError(status, code, message, MDC.get(RequestIdFilter.MDC_KEY), violations);
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
    }
}