package com.rbac_platform.lib.core.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs the handlers registered for an event, in order. The first exception stops the rest and propagates. */
public final class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final EventHandlerRegistry registry;
    private final UnhandledEventPolicy policy;

    public EventDispatcher(EventHandlerRegistry registry, UnhandledEventPolicy policy) {
        this.registry = registry;
        this.policy = policy;
    }

    public void dispatch(EventEnvelope event) throws Exception {
        if (!registry.hasSpecificHandler(event.eventType())) {
            if (policy == UnhandledEventPolicy.FAIL) {
                throw new UnhandledEventException(event.eventType());
            }
            log.warn("No handler for event type '{}' (event {})", event.eventType(), event.id());
        }
        for (EventHandler handler : registry.handlersFor(event.eventType())) {
            handler.handle(event);
        }
    }
}
