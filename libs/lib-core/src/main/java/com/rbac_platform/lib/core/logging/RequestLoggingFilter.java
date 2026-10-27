package com.rbac_platform.lib.core.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id, puts it in the log context (MDC) so each log line of the request carries it,
 * returns it to the client in X-Request-Id, and writes one summary line per request (or the failure with
 * its stack trace). Runs before Spring Security so rejected requests are logged too. The query string is
 * never logged: it can carry tokens or codes.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    // An incoming id (from a gateway or caller) is kept only if it cannot forge or break a log line.
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
            log.info("{} {} -> {} in {} ms", request.getMethod(), request.getRequestURI(), response.getStatus(), elapsedMs(start));
        } catch (IOException | ServletException | RuntimeException | Error e) {
            log.error("{} {} failed after {} ms", request.getMethod(), request.getRequestURI(), elapsedMs(start), e);
            throw e;
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
