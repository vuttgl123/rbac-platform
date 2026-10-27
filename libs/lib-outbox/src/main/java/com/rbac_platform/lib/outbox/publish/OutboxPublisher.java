package com.rbac_platform.lib.outbox.publish;

import com.rbac_platform.lib.core.event.EventEnvelope;

/**
 * Where the relay delivers claimed events: a log, the service's own handlers, later a broker. A publisher is chosen
 * by {@code rbac.outbox.publisher} or replaced by a service's own bean; adding a transport never touches the relay.
 *
 * <p>The relay calls it outside any transaction, one event at a time, from its single thread. Returning means
 * delivered: the row is deleted. Throwing means failed: the row is retried by the retry policy, or buried when the
 * failure is (or wraps) a {@code NonRetryableEventException}. Delivery is at-least-once.
 */
@FunctionalInterface
public interface OutboxPublisher {

    void publish(EventEnvelope event) throws Exception;
}
