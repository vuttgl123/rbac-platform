package com.rbac_platform.lib.outbox.relay;

import com.rbac_platform.lib.core.event.EventEnvelope;
import java.time.Duration;

/**
 * Runs around each delivery, on the relay thread: {@code before} in bean order, then {@code after} or
 * {@code onError} in reverse order, so the first interceptor wraps all the others. Logging context, timing and
 * metrics plug in here as beans; the relay itself never changes.
 *
 * <p>An exception from {@code before} or {@code after} fails the delivery like one from the publisher. One from
 * {@code onError} is logged and ignored, so it cannot take the rest of the batch down.
 */
public interface OutboxInterceptor {

    default void before(EventEnvelope event) {
    }

    /** @param took how long {@code publish} itself took */
    default void after(EventEnvelope event, Duration took) {
    }

    default void onError(EventEnvelope event, Throwable failure) {
    }
}
