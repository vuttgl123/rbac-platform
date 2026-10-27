package com.rbac_platform.lib.outbox.relay;

import com.rbac_platform.lib.core.event.EventEnvelope;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs each delivery: DEBUG with its duration when it succeeds, WARN with the event id and type when it fails. */
public class TimingOutboxInterceptor implements OutboxInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TimingOutboxInterceptor.class);

    @Override
    public void after(EventEnvelope event, Duration took) {
        if (log.isDebugEnabled()) {
            log.debug("Delivered event {} ({}) in {} ms", event.id(), event.eventType(), took.toMillis());
        }
    }

    @Override
    public void onError(EventEnvelope event, Throwable failure) {
        log.warn("Delivering event {} ({}) failed", event.id(), event.eventType(), failure);
    }
}
