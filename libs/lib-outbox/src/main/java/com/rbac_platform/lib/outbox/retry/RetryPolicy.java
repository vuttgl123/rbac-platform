package com.rbac_platform.lib.outbox.retry;

import com.rbac_platform.lib.outbox.OutboxRecord;
import java.time.Instant;

/** Decides the fate of a failed delivery. A service's own bean replaces the default exponential backoff. */
@FunctionalInterface
public interface RetryPolicy {

    /**
     * @param record  the claimed row; {@code attempts} counts the delivery that just failed
     * @param failure what the publisher (or an interceptor) threw
     * @param now     the time to schedule from
     */
    RetryDecision decide(OutboxRecord record, Throwable failure, Instant now);
}
