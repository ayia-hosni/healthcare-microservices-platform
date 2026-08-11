package com.healthplatform.common.web;

import com.healthplatform.common.dto.ErrorResponse;
import com.healthplatform.common.exception.BusinessException;
import com.healthplatform.common.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * Every business service in the platform follows this same error-shape convention; this used
 * to be a byte-identical class copy-pasted into each service's web package. identity-service
 * keeps its own copy — it maps BusinessException error codes to specific auth statuses
 * (401/403/409) rather than the flat 422 that's correct everywhere else, since "wrong password"
 * and "double-booked slot" aren't the same kind of failure.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), req, List.of());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest req) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getErrorCode(), ex.getMessage(), req, List.of());
    }

    /**
     * @PreAuthorize denials throw this from inside the controller method's AOP proxy invocation,
     * i.e. while Spring MVC is dispatching — so it never reaches Spring Security's
     * ExceptionTranslationFilter (that only sees exceptions that escape the servlet entirely, as
     * happens for the plain "no token" / anyRequest().authenticated() case). Without this handler
     * it falls through to handleUnexpected() below and every authorization failure comes back as
     * a misleading 500 instead of 403.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to perform this action", req, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage()).toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", req, details);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", req, List.of());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message,
                                                 HttpServletRequest req, List<String> details) {
        return ResponseEntity.status(status).body(new ErrorResponse(Instant.now(), status.value(), error, message, req.getRequestURI(), details));
    }
}
