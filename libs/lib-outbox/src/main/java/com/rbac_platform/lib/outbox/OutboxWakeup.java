package com.rbac_platform.lib.outbox;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lets the writer tell the relay of the same instance that a transaction with new events just committed, so the
 * relay need not wait for its next poll. Signals coalesce: any number of them before an {@link #await} wake it once.
 */
public class OutboxWakeup {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition signalled = lock.newCondition();
    private boolean pending;

    public void signal() {
        lock.lock();
        try {
            pending = true;
            signalled.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Waits at most {@code max}, returning early once signalled (a signal that came before the call counts). */
    public void await(Duration max) throws InterruptedException {
        long nanos = max.toNanos();
        lock.lockInterruptibly();
        try {
            while (!pending && nanos > 0) {
                nanos = signalled.awaitNanos(nanos);
            }
            pending = false;
        } finally {
            lock.unlock();
        }
    }
}
