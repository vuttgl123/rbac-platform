package com.rbac_platform.lib.security;

import com.rbac_platform.lib.core.error.ErrorCodes;
import com.rbac_platform.lib.core.error.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers a missing or rejected token with 401 problem+json. The code is {@code token_expired} only when expiry
 * was the sole problem (the client should refresh), otherwise {@code unauthenticated}. The detail never echoes
 * the exception message, which can carry issuer URLs or token fragments.
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemWriter problems;

    public ProblemAuthenticationEntryPoint(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e) {
        if (response.isCommitted()) {
            return;
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        if (isExpired(e)) {
            problems.write(request, response, HttpStatus.UNAUTHORIZED, ErrorCodes.TOKEN_EXPIRED, "Access token expired");
        } else if (e instanceof OAuth2AuthenticationException) {
            problems.write(request, response, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED, "Invalid access token");
        } else {
            problems.write(request, response, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED, "Authentication required");
        }
    }

    static boolean isExpired(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof JwtValidationException invalid) {
                return !invalid.getErrors().isEmpty() && invalid.getErrors().stream()
                        .map(OAuth2Error::getErrorCode)
                        .allMatch(TokenValidators.TOKEN_EXPIRED::equals);
            }
            if (t instanceof OAuth2AuthenticationException oauth
                    && TokenValidators.TOKEN_EXPIRED.equals(oauth.getError().getErrorCode())) {
                return true;
            }
        }
        return false;
    }
}
