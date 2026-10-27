package com.rbac_platform.lib.outbox.retry;

import java.time.Instant;
import java.util.Objects;

/** What to do with an event whose delivery failed. */
public sealed interface RetryDecision {

    /** Deliver again no earlier than {@code at}. */
    record RetryAt(Instant at) implements RetryDecision {

        public RetryAt {
            Objects.requireNonNull(at, "at");
        }
    }

    /** Stop trying: the row becomes {@code dead} until an operator requeues it. */
    record Bury(String reason) implements RetryDecision {

        public Bury {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
