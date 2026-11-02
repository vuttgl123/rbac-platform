package com.rbac_platform.lib.core.http;

import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import java.io.IOException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Propagates the caller's request id downstream so one user request can be traced across services, and
 * logs one line per outbound call. The query string is deliberately left out of the log: it may carry tokens.
 */
public class RequestIdForwardingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestIdForwardingInterceptor.class);

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String requestId = MDC.get(RequestLoggingFilter.MDC_KEY);
        if (requestId != null) {
            request.getHeaders().set(RequestLoggingFilter.HEADER, requestId);
        }
        long start = System.nanoTime();
        ClientHttpResponse response = execution.execute(request, body);
        long ms = (System.nanoTime() - start) / 1_000_000;
        URI uri = request.getURI();
        String target = uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath();
        log.info("{} {} -> {} in {} ms", request.getMethod(), target, response.getStatusCode().value(), ms);
        return response;
    }
}
