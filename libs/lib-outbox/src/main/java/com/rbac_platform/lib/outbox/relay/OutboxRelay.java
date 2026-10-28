package com.rbac_platform.lib.outbox.relay;

import com.rbac_platform.lib.core.event.EventEnvelope;
import com.rbac_platform.lib.outbox.OutboxProperties;
import com.rbac_platform.lib.outbox.OutboxRecord;
import com.rbac_platform.lib.outbox.OutboxStore;
import com.rbac_platform.lib.outbox.OutboxWakeup;
import com.rbac_platform.lib.outbox.publish.OutboxPublisher;
import com.rbac_platform.lib.outbox.retry.RetryDecision;
import com.rbac_platform.lib.outbox.retry.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the service's outbox: claims a batch under a lease, delivers each event outside any transaction, then
 * acknowledges the batch in one short transaction. The cycle is fixed; what varies is plugged in as strategies
 * (publisher, retry policy, interceptors). Delivery is at-least-once: a relay that dies mid-batch lets the lease
 * expire and the events are claimed again, so handlers must be idempotent on the event id.
 *
 * <p>When started, it runs on one daemon thread, {@code outbox-relay-<schema>}: a full batch is followed at once by
 * the next one; otherwise it waits for a writer's commit or {@code poll-interval}, whichever comes first. On stop it
 * claims nothing more, finishes the batch in flight and then reports stopped. It waits for that batch at most the
 * lease or the shutdown phase's timeout ({@code spring.lifecycle.timeout-per-shutdown-phase}), whichever is shorter:
 * past the phase timeout the context closes without it anyway, and past the lease the events are claimable again.
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    static final String MAX_ATTEMPTS_EXCEEDED = "max attempts exceeded without completing";

    private final OutboxStore store;
    private final OutboxWakeup wakeup;
    private final OutboxPublisher publisher;
    private final RetryPolicy retryPolicy;
    private final List<OutboxInterceptor> interceptors;
    private final List<OutboxInterceptor> unwinding;
    private final TransactionTemplate transactions;
    private final OutboxProperties properties;
    private final Duration stopWait;
    private final String threadName;

    private final Object lifecycle = new Object();
    /** The current run of the loop; null before the first start. Guarded by {@link #lifecycle}. */
    private Worker worker;

    /**
     * @param interceptors          in the order their {@code before} runs; {@code after} and {@code onError} run in
     *                              reverse
     * @param shutdownPhaseTimeout  how long the context waits for a lifecycle phase to stop before it goes on without
     *                              it; {@link #stop(Runnable)} never waits longer
     */
    public OutboxRelay(OutboxStore store, OutboxWakeup wakeup, OutboxPublisher publisher, RetryPolicy retryPolicy,
                       List<OutboxInterceptor> interceptors, TransactionTemplate transactions,
                       OutboxProperties properties, Duration shutdownPhaseTimeout) {
        this.store = Objects.requireNonNull(store, "store");
        this.wakeup = Objects.requireNonNull(wakeup, "wakeup");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.interceptors = List.copyOf(interceptors);
        this.unwinding = this.interceptors.reversed();
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.properties = Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(shutdownPhaseTimeout, "shutdownPhaseTimeout");
        this.stopWait = shutdownPhaseTimeout.compareTo(properties.lease()) < 0 ? shutdownPhaseTimeout : properties.lease();
        this.threadName = "outbox-relay-" + properties.schema();
    }

    /**
     * One claim, deliver, acknowledge cycle. Must be called outside any transaction: handlers open their own (through
     * {@code TenantScope}), and no connection is held while they run.
     *
     * @return the number of rows claimed, delivered or not
     */
    public int runOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxRelay.runOnce must run outside any transaction");
        }
        long started = System.nanoTime();
        UUID token = UUID.randomUUID();
        List<OutboxRecord> claimed = store.claim(properties.batchSize(), properties.lease(), token);
        if (claimed.isEmpty()) {
            return 0;
        }

        List<UUID> delivered = new ArrayList<>(claimed.size());
        List<Failure> failures = new ArrayList<>();
        for (OutboxRecord record : claimed) {
            if (record.attempts() > properties.maxAttempts()) {
                // Claimed more often than allowed without ever being acknowledged: it crashes or hangs the relay.
                failures.add(new Failure(record, MAX_ATTEMPTS_EXCEEDED, new RetryDecision.Bury(MAX_ATTEMPTS_EXCEEDED)));
                continue;
            }
            Failure failure = deliver(record);
            // A handler that was interrupted (or restored the flag before throwing) has failed as its own event. Left
            // set, the flag would fail every later blocking call on this thread: the rest of the batch's handlers and
            // the acknowledgement's connection checkout. Nothing else interrupts the relay's thread (stop uses a flag
            // and a wakeup), so clearing it loses no request to stop.
            Thread.interrupted();
            if (failure == null) {
                delivered.add(record.id());
            } else {
                failures.add(failure);
            }
        }

        acknowledge(token, delivered, failures);

        Duration took = Duration.ofNanos(System.nanoTime() - started);
        if (took.compareTo(properties.lease()) > 0) {
            log.warn("Outbox pass over {} events took {} ms, longer than the {} ms lease: the events may have been "
                    + "claimed again by another instance", claimed.size(), took.toMillis(), properties.lease().toMillis());
        }
        return claimed.size();
    }

    /**
     * Publishes one event between the interceptors; returns null when delivered. Whatever the event throws, Errors
     * included, becomes its own failure, so one bad event never holds back the rest of its batch; only a
     * {@link #rethrowIfFatal fatal} error escapes. The log context is restored whatever happens.
     */
    private Failure deliver(OutboxRecord record) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        EventEnvelope event = record.toEnvelope();
        try {
            for (OutboxInterceptor interceptor : interceptors) {
                interceptor.before(event);
            }
            long started = System.nanoTime();
            publisher.publish(event);
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            for (OutboxInterceptor interceptor : unwinding) {
                interceptor.after(event, took);
            }
            return null;
        } catch (Throwable e) {
            rethrowIfFatal(e);
            // An InterruptedException is not re-asserted on the thread: runOnce clears the flag after every event.
            for (OutboxInterceptor interceptor : unwinding) {
                try {
                    interceptor.onError(event, e);
                } catch (Throwable suppressed) {
                    rethrowIfFatal(suppressed);
                    log.warn("Outbox interceptor {} failed in onError for event {}", interceptor.getClass().getName(),
                            event.id(), suppressed);
                }
            }
            String message = e.getClass().getName() + ": " + e.getMessage();
            return new Failure(record, message, decide(record, e));
        } finally {
            if (previousMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousMdc);
            }
        }
    }

    /** The retry policy's decision; a policy that throws (or answers null) falls back to retrying after backoff.max. */
    private RetryDecision decide(OutboxRecord record, Throwable failure) {
        Instant now = Instant.now();
        try {
            return Objects.requireNonNull(retryPolicy.decide(record, failure, now), "retry decision");
        } catch (Throwable policyFailure) {
            rethrowIfFatal(policyFailure);
            Duration fallback = properties.backoff().max();
            log.error("Retry policy {} failed for outbox event {} ({}); retrying it in {} ms",
                    retryPolicy.getClass().getName(), record.id(), record.eventType(), fallback.toMillis(), policyFailure);
            return new RetryDecision.RetryAt(now.plus(fallback));
        }
    }

    /**
     * Errors the JVM may not survive (out of memory, internal error) are not one event's fault: they leave the batch
     * unacknowledged, to be claimed again when the lease expires. A stack overflow is the event's own.
     */
    private static void rethrowIfFatal(Throwable t) {
        if (t instanceof VirtualMachineError fatal && !(t instanceof StackOverflowError)) {
            throw fatal;
        }
    }

    /**
     * One short transaction of at most two statements, whatever the batch size: delete what was delivered, then
     * release or bury every failure at once.
     */
    private void acknowledge(UUID token, List<UUID> delivered, List<Failure> failures) {
        List<OutboxStore.Reschedule> reschedules = new ArrayList<>(failures.size());
        for (Failure failure : failures) {
            boolean dead = failure.decision() instanceof RetryDecision.Bury;
            Instant at = failure.decision() instanceof RetryDecision.RetryAt(Instant retryAt) ? retryAt : null;
            reschedules.add(new OutboxStore.Reschedule(failure.record().id(), failure.message(), at, dead));
        }
        int[] counts = new int[2];
        transactions.executeWithoutResult(status -> {
            counts[0] = store.complete(token, delivered);
            counts[1] = store.reschedule(token, reschedules);
        });
        if (counts[0] < delivered.size()) {
            log.warn("{} of {} delivered outbox events were no longer leased to this relay and may be delivered again",
                    delivered.size() - counts[0], delivered.size());
        }
        if (counts[1] < failures.size()) {
            log.warn("{} of {} failed outbox events were no longer leased to this relay; their failures were not recorded",
                    failures.size() - counts[1], failures.size());
        }
        for (Failure failure : failures) {
            if (failure.decision() instanceof RetryDecision.Bury(String reason)) {
                log.warn("Outbox event {} ({}) buried after {} attempts: {}", failure.record().id(),
                        failure.record().eventType(), failure.record().attempts(), reason);
            }
        }
    }

    private record Failure(OutboxRecord record, String message, RetryDecision decision) {
    }

    // ---- lifecycle ----

    @Override
    public boolean isAutoStartup() {
        return properties.enabled();
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (worker != null && worker.isAlive() && !worker.stopRequested) {
                return;
            }
            Worker next = new Worker();
            Thread.ofPlatform().name(threadName).daemon(true).start(next);
            worker = next;
        }
        log.info("Outbox relay started for schema {} (publisher {}, batch size {}, poll interval {} ms, lease {} ms)",
                properties.schema(), publisher.getClass().getName(), properties.batchSize(),
                properties.pollInterval().toMillis(), properties.lease().toMillis());
    }

    @Override
    public void stop() {
        CountDownLatch stopped = new CountDownLatch(1);
        stop(stopped::countDown);
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Claims nothing more and returns at once; {@code callback} runs once the batch in flight is acknowledged, or
     * after the lease or the shutdown phase timeout, whichever is shorter, if it is not by then.
     */
    @Override
    public void stop(Runnable callback) {
        Worker current;
        synchronized (lifecycle) {
            current = worker;
            if (current == null || !current.isAlive()) {
                callback.run();
                return;
            }
            current.stopRequested = true;
        }
        wakeup.signal();
        long waitMillis = stopWait.toMillis();
        // A copy, so the timeout never marks the worker itself finished while its thread still runs.
        current.finished.copy().orTimeout(waitMillis, TimeUnit.MILLISECONDS).whenComplete((done, failure) -> {
            if (failure instanceof TimeoutException) {
                log.warn("Outbox relay for schema {} did not finish its batch within {} ms; stopping without it, its "
                        + "events will be claimed again once their lease expires", properties.schema(), waitMillis);
            }
            callback.run();
        });
    }

    /** True from start until the loop thread has exited (which, on stop, is after the batch in flight). */
    @Override
    public boolean isRunning() {
        synchronized (lifecycle) {
            return worker != null && worker.isAlive();
        }
    }

    /** One run of the relay loop, from a start to the matching stop. */
    private final class Worker implements Runnable {

        volatile boolean stopRequested;
        final CompletableFuture<Void> finished = new CompletableFuture<>();

        boolean isAlive() {
            return !finished.isDone();
        }

        @Override
        public void run() {
            try {
                while (!stopRequested) {
                    pass();
                }
            } finally {
                try {
                    log.info("Outbox relay stopped for schema {}", properties.schema());
                } finally {
                    finished.complete(null);
                }
            }
        }

        /** One cycle and the wait after it. Never throws: the thread must outlive any failure. */
        private void pass() {
            try {
                if (runOnce() >= properties.batchSize()) {
                    return; // A full batch: more may be due, go again at once.
                }
            } catch (Throwable t) {
                try {
                    log.error("Outbox relay pass failed for schema {}; trying again in {} ms", properties.schema(),
                            properties.pollInterval().toMillis(), t);
                } catch (Throwable ignored) {
                    // Even logging failed (out of memory, say); the loop must still go on.
                }
            }
            if (stopRequested) {
                return;
            }
            try {
                wakeup.await(properties.pollInterval());
            } catch (InterruptedException e) {
                // Nobody but this class owns the thread: the loop re-checks whether it was asked to stop.
            }
        }
    }
}
