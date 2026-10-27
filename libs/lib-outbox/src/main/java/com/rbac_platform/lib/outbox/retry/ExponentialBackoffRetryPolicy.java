package com.rbac_platform.lib.outbox.retry;

import com.rbac_platform.lib.core.event.NonRetryableEventException;
import com.rbac_platform.lib.outbox.OutboxRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Retries with a delay that doubles from {@code initial} up to {@code max}, shortened by up to 20% at random so that
 * events failing together do not all come back together. Buries an event once it has had {@code maxAttempts}
 * deliveries, or at once when the failure is, or is caused by, a {@link NonRetryableEventException}.
 */
public class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private static final double JITTER = 0.2;
    /** Bounds the walk down a cause chain, in case it loops. */
    private static final int MAX_CAUSE_DEPTH = 64;

    private final long initialNanos;
    private final long maxNanos;
    private final int maxAttempts;
    private final RandomGenerator random;

    /** @param random source of the jitter; shared by every caller, so it must be safe for the threads that call */
    public ExponentialBackoffRetryPolicy(Duration initial, Duration max, int maxAttempts, RandomGenerator random) {
        requirePositive(initial, "initial");
        requirePositive(max, "max");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1 but was " + maxAttempts);
        }
        this.initialNanos = initial.toNanos();
        this.maxNanos = max.toNanos();
        this.maxAttempts = maxAttempts;
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public RetryDecision decide(OutboxRecord record, Throwable failure, Instant now) {
        if (isNonRetryable(failure)) {
            return new RetryDecision.Bury("non-retryable failure");
        }
        if (record.attempts() >= maxAttempts) {
            return new RetryDecision.Bury("gave up after " + record.attempts() + " attempts");
        }
        long delay = delayNanos(record.attempts());
        long jittered = delay - (long) (delay * JITTER * random.nextDouble());
        return new RetryDecision.RetryAt(now.plusNanos(jittered));
    }

    /** {@code min(max, initial * 2^(attempts - 1))}, without overflowing however many attempts there were. */
    private long delayNanos(int attempts) {
        int shift = Math.max(0, attempts - 1);
        if (shift >= Long.SIZE - 1 || initialNanos > (maxNanos >> shift)) {
            return maxNanos;
        }
        return Math.min(maxNanos, initialNanos << shift);
    }

    private static boolean isNonRetryable(Throwable failure) {
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (t instanceof NonRetryableEventException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration but was " + value);
        }
    }
}
