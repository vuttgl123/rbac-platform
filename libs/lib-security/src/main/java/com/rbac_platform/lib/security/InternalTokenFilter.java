package com.rbac_platform.lib.security;

import com.rbac_platform.lib.core.error.ErrorCodes;
import com.rbac_platform.lib.core.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code /internal/**}, the endpoints services call on each other: the request must carry the shared
 * secret in {@code X-Internal-Token}. A user's bearer token never substitutes for it, so even a valid access
 * token (or one combined with a wrong secret) is answered 401 here and the request never reaches a controller.
 * The secret is compared in constant time so response timing does not leak it.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private static final RequestMatcher INTERNAL = PathPatternRequestMatcher.withDefaults().matcher("/internal/**");

    private final byte[] expected;
    private final ProblemWriter problems;

    public InternalTokenFilter(String internalToken, ProblemWriter problems) {
        this.expected = internalToken.getBytes(StandardCharsets.UTF_8);
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !INTERNAL.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented != null && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        problems.write(request, response, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED, "Internal token required");
    }
}
