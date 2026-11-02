package com.rbac_platform.lib.core.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The container's error page ({@code /error}), replacing Boot's BasicErrorController. It answers what never
 * reached {@link ApiExceptionHandler}: an exception thrown by a filter (a database failure during token checks,
 * say), a request the security firewall rejected, a container {@code sendError}. Those get the same
 * problem+json as every other error: the status the container recorded, the code for that status
 * ({@link ErrorCodes#forStatus}) and the request id. The exception and the error message are never echoed, and
 * the body does not depend on the client's {@code Accept} header.
 */
@Controller
@RequestMapping("${server.error.path:${error.path:/error}}")
public class ProblemErrorController implements ErrorController {

    private final ProblemWriter problems;

    public ProblemErrorController(ProblemWriter problems) {
        this.problems = problems;
    }

    @RequestMapping
    public void error(HttpServletRequest request, HttpServletResponse response) {
        if (response.isCommitted()) {
            return;
        }
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        // Requested directly rather than dispatched by the container: there is no error to report.
        HttpStatus status = code instanceof Integer value ? ErrorCodes.statusOf(value) : HttpStatus.NOT_FOUND;
        String detail = status.is5xxServerError() ? "Unexpected error" : "Request failed";
        response.resetBuffer();
        problems.write(request, response, status, ErrorCodes.forStatus(status), detail);
    }
}
