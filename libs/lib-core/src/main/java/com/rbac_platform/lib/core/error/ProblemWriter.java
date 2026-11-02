package com.rbac_platform.lib.core.error;

import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the {@code application/problem+json} body used for every error, and writes it directly for code
 * that runs outside Spring MVC (security filters). Fields: type, title, status, detail, code, requestId and,
 * only when there are any, errors.
 */
public class ProblemWriter {

    private final JsonMapper json;

    public ProblemWriter(JsonMapper json) {
        this.json = json;
    }

    public ProblemDetail problem(HttpServletRequest request, HttpStatus status, String code, String detail,
            List<FieldViolation> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        Object requestId = request.getAttribute(RequestLoggingFilter.REQUEST_ATTRIBUTE);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }

    /** Flattens the problem into a plain map so the output does not depend on the mapper's ProblemDetail mixin. */
    private static Map<String, Object> body(ProblemDetail problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        return body;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
            String detail) {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            response.getOutputStream().write(json.writeValueAsBytes(body(problem(request, status, code, detail, List.of()))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
