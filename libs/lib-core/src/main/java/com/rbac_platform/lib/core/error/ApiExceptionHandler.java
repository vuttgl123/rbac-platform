package com.rbac_platform.lib.core.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every exception raised inside Spring MVC to a problem response with a stable code. Unexpected errors
 * are logged with their stack and answered with a generic message so no internals leak.
 *
 * <p>Lowest precedence because it handles {@link Exception}: a service's own {@code @RestControllerAdvice} must
 * be ordered above it (any {@code @Order} value lower than {@link Ordered#LOWEST_PRECEDENCE}) to be consulted
 * first for the exceptions it maps.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ProblemWriter problems;

    public ApiExceptionHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> apiException(ApiException e, HttpServletRequest request) {
        return respond(request, e.status(), e.code(), e.getMessage(), e.errors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        e.getBindingResult().getAllErrors().forEach(error -> violations.add(violation(error)));
        return respond(request, HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "Request validation failed", violations);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> invalidArguments(HandlerMethodValidationException e, HttpServletRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        e.getParameterValidationResults().forEach(result -> {
            String parameter = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> {
                FieldViolation violation = violation(error);
                violations.add(error instanceof FieldError || parameter == null
                        ? violation : new FieldViolation(parameter, violation.code()));
            });
        });
        return respond(request, HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "Request validation failed", violations);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> badRequest(Exception e, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "Malformed request", List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "Request validation failed",
                List.of(new FieldViolation(e.getName(), "type_mismatch")));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> noResource(NoResourceFoundException e, HttpServletRequest request) {
        return respond(request, HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "Resource not found", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> methodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return respond(request, HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.METHOD_NOT_ALLOWED, "Method not allowed", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) throws Exception {
        if (isSecurityException(e)) {
            throw e; // lib-core has no Spring Security dependency; let the security filter chain answer
        }
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatus status = ErrorCodes.statusOf(errorResponse.getStatusCode().value());
            if (status.is5xxServerError()) {
                LOG.error("Server error for {} {}", request.getMethod(), request.getRequestURI(), e);
                return respond(request, status, ErrorCodes.INTERNAL_ERROR, "Unexpected error", List.of());
            }
            return respond(request, status, ErrorCodes.forStatus(status), "Request failed", List.of());
        }
        LOG.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(request, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR, "Unexpected error", List.of());
    }

    private static boolean isSecurityException(Throwable e) {
        for (Class<?> c = e.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.equals("org.springframework.security.access.AccessDeniedException")
                    || name.equals("org.springframework.security.core.AuthenticationException")) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<ProblemDetail> respond(HttpServletRequest request, HttpStatus status, String code,
            String detail, List<FieldViolation> errors) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problems.problem(request, status, code, detail, errors));
    }

    private static FieldViolation violation(MessageSourceResolvable error) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : "";
        String[] codes = error.getCodes();
        String constraint = codes == null || codes.length == 0 ? "invalid" : codes[codes.length - 1];
        return new FieldViolation(field, snakeCase(constraint));
    }

    private static String snakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
