package com.rbac_platform.lib.outbox.publish;

import com.rbac_platform.lib.core.event.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The default publisher ({@code rbac.outbox.publisher=log}): writes one INFO line per event and delivers nothing. */
public class LoggingOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxPublisher.class);

    @Override
    public void publish(EventEnvelope event) {
        log.info("Outbox event {} {}/{} (event {})", event.eventType(), event.aggregateType(), event.aggregateId(),
                event.id());
    }
}
