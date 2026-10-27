package com.rbac_platform.lib.core.event;

import java.util.Set;

/**
 * Processes events of the types it declares. Adding a kind of processing means adding one bean of this type;
 * nothing dispatches on the event type itself.
 *
 * <p>Delivery is at-least-once: the same event can reach a handler more than once, so handlers must be
 * idempotent on {@link EventEnvelope#id()}. Throwing makes the delivery fail and be retried, unless the
 * exception is a {@link NonRetryableEventException}.
 */
public interface EventHandler {

    /** Declared in {@link #eventTypes()} to receive every event, including types no other handler claims. */
    String ANY = "*";

    /** The event types this handler processes, or {@link #ANY}. Never null or empty. */
    Set<String> eventTypes();

    void handle(EventEnvelope event) throws Exception;
}
