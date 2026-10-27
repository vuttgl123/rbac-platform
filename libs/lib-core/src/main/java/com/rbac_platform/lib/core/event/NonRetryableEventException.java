package com.rbac_platform.lib.core.event;

/** Thrown by a handler when retrying cannot help, such as a payload it can never process. */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
