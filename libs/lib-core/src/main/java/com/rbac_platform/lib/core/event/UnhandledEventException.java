package com.rbac_platform.lib.core.event;

/** No handler declares the event's type and the policy is {@link UnhandledEventPolicy#FAIL}. Retryable. */
public class UnhandledEventException extends RuntimeException {

    public UnhandledEventException(String eventType) {
        super("No handler for event type '" + eventType + "'");
    }
}
