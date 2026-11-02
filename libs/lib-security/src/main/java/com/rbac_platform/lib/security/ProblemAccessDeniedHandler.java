package com.rbac_platform.lib.security;

import com.rbac_platform.lib.core.error.ErrorCodes;
import com.rbac_platform.lib.core.error.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers an authenticated caller that is not allowed with 403 problem+json {@code forbidden}, including
 * {@link AccessDeniedException}s thrown from controllers (lib-core's exception handler rethrows them to here).
 */
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemWriter problems;

    public ProblemAccessDeniedHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e) {
        if (response.isCommitted()) {
            return;
        }
        problems.write(request, response, HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "Access denied");
    }
}
