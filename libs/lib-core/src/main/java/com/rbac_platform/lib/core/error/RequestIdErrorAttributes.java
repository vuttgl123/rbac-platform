package com.rbac_platform.lib.core.error;

import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import java.util.Map;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.web.context.request.WebRequest;

/**
 * Boot's standard error body plus the id of the request that failed, so a client can quote it and the
 * matching log lines can be found. The id is read from the request attribute set by RequestLoggingFilter:
 * the MDC is already cleared when the container dispatches to the error page.
 */
public class RequestIdErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(webRequest, options);
        Object requestId = webRequest.getAttribute(RequestLoggingFilter.REQUEST_ATTRIBUTE, WebRequest.SCOPE_REQUEST);
        if (requestId != null) {
            attributes.put("requestId", requestId);
        }
        return attributes;
    }
}
