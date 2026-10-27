package com.rbac_platform.lib.outbox.publish;

import com.rbac_platform.lib.core.event.EventDispatcher;
import com.rbac_platform.lib.core.event.EventEnvelope;
import java.util.Objects;

/**
 * Delivers events to the service's own {@code EventHandler} beans ({@code rbac.outbox.publisher=in-process}), through
 * the same dispatcher a broker consumer uses, so handlers stay unchanged when the transport changes.
 */
public class InProcessOutboxPublisher implements OutboxPublisher {

    private final EventDispatcher dispatcher;

    public InProcessOutboxPublisher(EventDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public void publish(EventEnvelope event) throws Exception {
        dispatcher.dispatch(event);
    }
}
