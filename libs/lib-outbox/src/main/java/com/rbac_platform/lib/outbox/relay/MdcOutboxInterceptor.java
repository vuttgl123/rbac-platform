package com.rbac_platform.lib.outbox.relay;

import com.rbac_platform.lib.core.event.EventEnvelope;
import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import com.rbac_platform.lib.db.TenantScope;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Puts the event's request id, tenant and id into the log context, so every line logged while the event is delivered
 * joins up with the request that caused it. Runs first so that it wraps every other interceptor. Nothing to undo:
 * the relay restores the previous log context after each event.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcOutboxInterceptor implements OutboxInterceptor {

    public static final String EVENT_ID = "eventId";

    @Override
    public void before(EventEnvelope event) {
        String requestId = event.headers().get(RequestLoggingFilter.MDC_KEY);
        if (requestId != null) {
            MDC.put(RequestLoggingFilter.MDC_KEY, requestId);
        } else {
            // Not the request id of whatever ran on this thread before.
            MDC.remove(RequestLoggingFilter.MDC_KEY);
        }
        MDC.put(TenantScope.MDC_KEY, event.tenantId().toString());
        MDC.put(EVENT_ID, event.id().toString());
    }
}
