package com.rbac_platform.lib.core.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Test fixture: fails before the request reaches Spring MVC, the way a security filter or the container can, so
 * the error page (not ApiExceptionHandler) has to answer.
 */
@Component
class FailingFilter extends OncePerRequestFilter {

    static final String SECRET = "filter-secret-detail";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        switch (request.getRequestURI()) {
            case "/test/filter/boom" -> throw new IllegalStateException(SECRET);
            case "/test/filter/unauthorized" -> response.sendError(401, SECRET);
            case "/test/filter/teapot" -> response.sendError(418, SECRET);
            case "/test/filter/unavailable" -> response.sendError(503, SECRET);
            default -> chain.doFilter(request, response);
        }
    }
}
