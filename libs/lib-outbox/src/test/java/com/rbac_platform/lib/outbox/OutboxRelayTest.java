package com.rbac_platform.lib.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.rbac_platform.lib.core.event.EventEnvelope;
import com.rbac_platform.lib.core.event.EventHandler;
import com.rbac_platform.lib.core.event.NonRetryableEventException;
import com.rbac_platform.lib.db.TenantScope;
import com.rbac_platform.lib.outbox.publish.OutboxPublisher;
import com.rbac_platform.lib.outbox.relay.OutboxInterceptor;
import com.rbac_platform.lib.outbox.relay.OutboxRelay;
import com.rbac_platform.lib.outbox.retry.RetryPolicy;
import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import com.rbac_platform.testsupport.Sql;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The relay against the real outbox, one nested class per application context. Most tests drive {@code runOnce()}
 * on a relay that is not started ({@code enabled=false}); the lifecycle tests start one in a context that is closed
 * after it, so no live relay outlives its test and claims rows other tests depend on (the claim sees every tenant).
 * Test classes of this module run one after another, which the outbox cleanup below relies on.
 *
 * <p>This outer class holds no Spring state: Spring injects the enclosing instance of a {@code @Nested} test from the
 * nested class's own context, so each nested class keeps its beans to itself.
 */
class OutboxRelayTest {

    static final MigratedDatabase db = MigratedDatabase.get();

    static void registerDatabase(DynamicPropertyRegistry registry) {
        AppDatabase.register(registry, "identity_app");
        registry.add("rbac.outbox.schema", () -> "identity");
    }

    /** A tenant of the current test's own. */
    UUID tenant;

    @BeforeEach
    void freshOutboxAndTenant() {
        // The claim sees every tenant, so start from an empty queue.
        emptyOutbox();
        tenant = db.insertTenant();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** Records every envelope it is given; fails or blocks on the event types it is told to. */
    static final class RecordingPublisher implements OutboxPublisher {
        final List<EventEnvelope> seen = new CopyOnWriteArrayList<>();
        final Map<String, Throwable> failures = new ConcurrentHashMap<>();
        final Map<String, Gate> gates = new ConcurrentHashMap<>();
        volatile Consumer<EventEnvelope> probe = event -> {
        };

        @Override
        public void publish(EventEnvelope event) throws Exception {
            seen.add(event);
            probe.accept(event);
            Gate gate = gates.get(event.eventType());
            if (gate != null) {
                gate.pass();
            }
            Throwable failure = failures.get(event.eventType());
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw (Exception) failure;
            }
        }

        List<UUID> seenIds() {
            return seen.stream().map(EventEnvelope::id).toList();
        }

        void reset() {
            seen.clear();
            failures.clear();
            gates.clear();
            probe = event -> {
            };
        }
    }

    /** An ordered record of what happened, shared between test beans and the test. */
    static final class Trace {
        final List<String> entries = new CopyOnWriteArrayList<>();
    }

    /** Holds a publish until released, and tells when one entered and when it returned. */
    static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Trace timeline;

        Gate(Trace timeline) {
            this.timeline = timeline;
        }

        void pass() throws InterruptedException {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            timeline.entries.add("publish returned");
        }
    }

    /** Writes into a shared trace, to check the order in which interceptors run. */
    record TracingInterceptor(String name, Trace trace) implements OutboxInterceptor {
        @Override
        public void before(EventEnvelope event) {
            trace.entries.add(name + ".before");
        }

        @Override
        public void after(EventEnvelope event, Duration took) {
            trace.entries.add(name + ".after");
        }

        @Override
        public void onError(EventEnvelope event, Throwable failure) {
            trace.entries.add(name + ".onError");
        }
    }

    @TestConfiguration
    static class RecordingConfig {
        @Bean
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }

        @Bean
        Trace interceptorTrace() {
            return new Trace();
        }

        @Bean
        @Order(2)
        OutboxInterceptor secondInterceptor(Trace interceptorTrace) {
            return new TracingInterceptor("second", interceptorTrace);
        }

        @Bean
        @Order(1)
        OutboxInterceptor firstInterceptor(Trace interceptorTrace) {
            return new TracingInterceptor("first", interceptorTrace);
        }

        /** Innermost, so it unwinds first; its onError breaks, with an Error, on the one event type that asks for it. */
        @Bean
        @Order(3)
        OutboxInterceptor brokenOnErrorInterceptor() {
            return new OutboxInterceptor() {
                @Override
                public void onError(EventEnvelope event, Throwable failure) {
                    if (event.eventType().equals(BROKEN_ON_ERROR)) {
                        throw new AssertionError("interceptor bug");
                    }
                }
            };
        }
    }

    static final String BROKEN_ON_ERROR = "test.broken-on-error";

    // ---- helpers over the superuser connection: they see every tenant and bypass row-level security ----

    static void emptyOutbox() {
        db.inTx(null, null, c -> Sql.exec(c, "delete from identity.outbox"));
    }

    static long rows() {
        return db.inTx(null, null, c -> Sql.count(c, "select count(*) from identity.outbox"));
    }

    static boolean exists(UUID id) {
        return db.inTx(null, null, c -> Sql.count(c, "select count(*) from identity.outbox where id = ?", id)) == 1;
    }

    static String status(UUID id) {
        return db.inTx(null, null, c -> Sql.one(c, String.class, "select status from identity.outbox where id = ?", id));
    }

    static int attempts(UUID id) {
        return db.inTx(null, null, c -> Sql.one(c, Integer.class, "select attempts from identity.outbox where id = ?", id));
    }

    static <T> T column(UUID id, String column, Class<T> type) {
        // column is a literal from the test code, never input
        return db.inTx(null, null, c -> Sql.one(c, type, "select " + column + " from identity.outbox where id = ?", id));
    }

    /**
     * Appends one event for a started relay, which may deliver and delete the row before any read-back could find
     * it; so it returns the event's unique aggregate id, to look for in what the publisher saw.
     */
    static String appendForLiveRelay(TenantScope scope, OutboxWriter writer, JsonMapper json, UUID tenant, String eventType) {
        String aggregateId = UUID.randomUUID().toString();
        scope.call(tenant, () -> {
            writer.append(OutboxMessage.of("test", aggregateId, eventType, json.readTree("{}")));
            return null;
        });
        return aggregateId;
    }

    static EventEnvelope seenFor(RecordingPublisher publisher, String aggregateId) {
        return publisher.seen.stream().filter(e -> e.aggregateId().equals(aggregateId)).findFirst().orElse(null);
    }

    /**
     * Appends through the service's own path (TenantScope + OutboxWriter) and returns the ids, in append order. Only
     * for a relay that is not started: the ids are read back from the outbox.
     */
    static List<UUID> append(TenantScope scope, OutboxWriter writer, JsonMapper json, UUID tenant, String... eventTypes) {
        String batch = UUID.randomUUID().toString();
        List<OutboxMessage> messages = new ArrayList<>();
        for (int i = 0; i < eventTypes.length; i++) {
            messages.add(new OutboxMessage("test", batch + "-" + i, eventTypes[i], json.readTree("{\"n\":" + i + "}"),
                    Map.of("requestId", "req-" + batch)));
        }
        scope.call(tenant, () -> {
            writer.appendAll(messages);
            return null;
        });
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < eventTypes.length; i++) {
            String aggregateId = batch + "-" + i;
            ids.add(db.inTx(null, null, c -> Sql.one(c, UUID.class,
                    "select id from identity.outbox where aggregate_id = ?", aggregateId)));
        }
        return ids;
    }

    // ---- runOnce driven by the test, on a relay that is not started ----

    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = OutboxTestApplication.class, properties = "rbac.outbox.enabled=false")
    @Import(RecordingConfig.class)
    class Driven {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registerDatabase(registry);
        }

        @Autowired
        private OutboxRelay relay;
        @Autowired
        private OutboxStore store;
        @Autowired
        private OutboxWriter writer;
        @Autowired
        private TenantScope tenantScope;
        @Autowired
        private JsonMapper json;
        @Autowired
        private RecordingPublisher publisher;
        @Autowired
        private Trace interceptorTrace;

        @BeforeEach
        void resetBeans() {
            publisher.reset();
            interceptorTrace.entries.clear();
        }

        private List<UUID> append(String... eventTypes) {
            return OutboxRelayTest.append(tenantScope, writer, json, tenant, eventTypes);
        }

        @Test
        void publishedEventsAreDeleted() {
            List<UUID> ids = append("test.ok", "test.ok");

            assertThat(relay.runOnce()).isEqualTo(2);

            assertThat(publisher.seenIds()).containsExactlyInAnyOrderElementsOf(ids);
            // Postgres orders uuids byte-wise, which is the order of their lower-case text.
            assertThat(publisher.seenIds().stream().map(UUID::toString).toList()).isSorted();
            assertThat(rows()).isZero();
            assertThat(relay.runOnce()).isZero();
        }

        @Test
        void failedEventIsRescheduledWithBackoffAndError() {
            publisher.failures.put("test.fail", new IllegalStateException("downstream unavailable"));
            UUID id = append("test.fail").get(0);

            Instant t0 = Instant.now();
            assertThat(relay.runOnce()).isEqualTo(1);
            Instant t1 = Instant.now();

            assertThat(status(id)).isEqualTo("pending");
            assertThat(attempts(id)).isEqualTo(1);
            assertThat(column(id, "last_error", String.class))
                    .isEqualTo("java.lang.IllegalStateException: downstream unavailable");
            assertThat(column(id, "claim_token", UUID.class)).isNull();
            // Postgres keeps microseconds: widen each bound by the rounding, no more.
            Instant next = column(id, "next_attempt_at", OffsetDateTime.class).toInstant();
            assertThat(next).isBetween(t0.truncatedTo(ChronoUnit.MICROS).plusMillis(800),
                    t1.plus(1, ChronoUnit.MICROS).plusSeconds(1));
        }

        @Test
        void oneFailingEventDoesNotHoldBackTheRestOfItsBatch() {
            publisher.failures.put("test.fail", new IllegalStateException("only this one"));
            List<UUID> ids = append("test.ok", "test.fail", "test.ok");

            assertThat(relay.runOnce()).isEqualTo(3);

            assertThat(publisher.seenIds()).containsExactlyInAnyOrderElementsOf(ids);
            assertThat(exists(ids.get(0))).isFalse();
            assertThat(exists(ids.get(2))).isFalse();
            assertThat(status(ids.get(1))).isEqualTo("pending");
            assertThat(rows()).isEqualTo(1);
        }

        @Test
        void nonRetryableFailureBuriesTheEvent() {
            publisher.failures.put("test.poison", new NonRetryableEventException("payload can never be processed"));
            UUID id = append("test.poison").get(0);

            relay.runOnce();

            assertThat(status(id)).isEqualTo("dead");
            assertThat(column(id, "last_error", String.class)).contains("payload can never be processed");
            assertThat(relay.runOnce()).as("buried rows are never claimed").isZero();
        }

        @Test
        void eventPastMaxAttemptsIsBuriedWithoutPublishing() {
            UUID id = append("test.ok").get(0);
            db.inTx(null, null, c -> Sql.exec(c, "update identity.outbox set attempts = 10 where id = ?", id));

            assertThat(relay.runOnce()).isEqualTo(1);

            assertThat(publisher.seen).isEmpty();
            assertThat(attempts(id)).isEqualTo(11);
            assertThat(status(id)).isEqualTo("dead");
            assertThat(column(id, "last_error", String.class)).isEqualTo("max attempts exceeded without completing");
        }

        @Test
        void eventIsRetriedAfterItsLeaseExpires() throws InterruptedException {
            UUID id = append("test.ok").get(0);
            // A relay that claimed the row and died before completing it.
            assertThat(store.claim(1, Duration.ofSeconds(1), UUID.randomUUID())).hasSize(1);

            assertThat(relay.runOnce()).as("leased to the dead relay").isZero();
            assertThat(publisher.seen).isEmpty();

            Thread.sleep(1200);
            List<Integer> attemptsDuringPublish = new CopyOnWriteArrayList<>();
            publisher.probe = event -> attemptsDuringPublish.add(attempts(event.id()));

            assertThat(relay.runOnce()).isEqualTo(1);

            assertThat(publisher.seenIds()).containsExactly(id);
            assertThat(attemptsDuringPublish).containsExactly(2);
            assertThat(exists(id)).isFalse();
        }

        @Test
        void mdcCarriesTheRequestIdIntoThePublisher() {
            List<UUID> ids = append("test.ok", "test.fail");
            publisher.failures.put("test.fail", new IllegalStateException("fails with the MDC set"));
            List<Map<String, String>> captured = new CopyOnWriteArrayList<>();
            publisher.probe = event -> captured.add(Map.of(
                    "requestId", String.valueOf(MDC.get("requestId")),
                    "eventId", String.valueOf(MDC.get("eventId")),
                    "tenantId", String.valueOf(MDC.get("tenantId")),
                    "header", event.headers().get("requestId"),
                    "id", event.id().toString()));
            MDC.put("caller", "kept");

            relay.runOnce();

            assertThat(captured).hasSize(2).allSatisfy(seen -> {
                assertThat(seen.get("requestId")).isEqualTo(seen.get("header"));
                assertThat(seen.get("eventId")).isEqualTo(seen.get("id"));
                assertThat(seen.get("tenantId")).isEqualTo(tenant.toString());
            });
            assertThat(captured).extracting(seen -> UUID.fromString(seen.get("id"))).containsExactlyInAnyOrderElementsOf(ids);
            // Restored after each event, including the one whose publish threw.
            assertThat(MDC.get("eventId")).isNull();
            assertThat(MDC.get("requestId")).isNull();
            assertThat(MDC.get("tenantId")).isNull();
            assertThat(MDC.get("caller")).isEqualTo("kept");
        }

        @Test
        void interceptorsRunInBeanOrderAndUnwindInReverse() {
            publisher.failures.put("test.fail", new IllegalStateException("boom"));
            append("test.ok");
            relay.runOnce();
            assertThat(interceptorTrace.entries).containsExactly("first.before", "second.before", "second.after", "first.after");

            interceptorTrace.entries.clear();
            append("test.fail");
            relay.runOnce();
            assertThat(interceptorTrace.entries)
                    .containsExactly("first.before", "second.before", "second.onError", "first.onError");
        }

        @Test
        void anErrorFromThePublisherDoesNotHoldBackTheRestOfItsBatch() {
            publisher.failures.put("test.error", new AssertionError("handler bug"));
            List<UUID> ids = append("test.ok", "test.error", "test.ok");

            assertThat(relay.runOnce()).isEqualTo(3);

            assertThat(exists(ids.get(0))).isFalse();
            assertThat(exists(ids.get(2))).isFalse();
            assertThat(status(ids.get(1))).isEqualTo("pending");
            assertThat(attempts(ids.get(1))).isEqualTo(1);
            assertThat(column(ids.get(1), "claim_token", UUID.class)).isNull();
            assertThat(column(ids.get(1), "last_error", String.class)).isEqualTo("java.lang.AssertionError: handler bug");
        }

        @Test
        void aThrowingOnErrorInterceptorDoesNotHoldBackTheBatch() {
            publisher.failures.put(BROKEN_ON_ERROR, new IllegalStateException("downstream unavailable"));
            List<UUID> ids = append("test.ok", BROKEN_ON_ERROR, "test.ok");

            assertThat(relay.runOnce()).isEqualTo(3);

            assertThat(exists(ids.get(0))).isFalse();
            assertThat(exists(ids.get(2))).isFalse();
            assertThat(status(ids.get(1))).isEqualTo("pending");
            assertThat(column(ids.get(1), "last_error", String.class))
                    .isEqualTo("java.lang.IllegalStateException: downstream unavailable");
            // The interceptors outside the broken one still unwind.
            assertThat(interceptorTrace.entries).containsSubsequence("second.onError", "first.onError");
        }

        @Test
        void anInterruptedHandlerDoesNotPoisonTheRestOfItsBatch() throws InterruptedException {
            // One handler throws InterruptedException; another follows the usual idiom of restoring the flag and
            // throwing something unchecked. Each is followed by an event whose handler would fail at once on a
            // blocking call if the flag were still set. Separate appends a few ms apart keep that order in the ids.
            publisher.failures.put("test.interrupted", new InterruptedException("handler interrupted"));
            publisher.failures.put("test.reinterrupts", new IllegalStateException("interrupted, gave up"));
            Map<UUID, Boolean> interruptedOnEntry = new ConcurrentHashMap<>();
            publisher.probe = event -> {
                interruptedOnEntry.put(event.id(), Thread.currentThread().isInterrupted());
                if (event.eventType().equals("test.reinterrupts")) {
                    Thread.currentThread().interrupt();
                }
            };
            List<UUID> ids = new ArrayList<>();
            for (String type : List.of("test.interrupted", "test.ok", "test.reinterrupts", "test.ok")) {
                ids.addAll(append(type));
                Thread.sleep(5);
            }

            try {
                assertThat(relay.runOnce()).isEqualTo(4);
                assertThat(Thread.currentThread().isInterrupted()).as("flag left on the relay thread").isFalse();
            } finally {
                Thread.interrupted();
            }

            assertThat(publisher.seenIds()).containsExactlyElementsOf(ids);
            assertThat(interruptedOnEntry).containsOnlyKeys(ids).doesNotContainValue(true);
            assertThat(exists(ids.get(1))).as("delivered and acknowledged").isFalse();
            assertThat(exists(ids.get(3))).as("delivered and acknowledged").isFalse();
            assertThat(column(ids.get(0), "last_error", String.class))
                    .isEqualTo("java.lang.InterruptedException: handler interrupted");
            assertThat(column(ids.get(2), "last_error", String.class))
                    .isEqualTo("java.lang.IllegalStateException: interrupted, gave up");
            assertThat(column(ids.get(0), "claim_token", UUID.class)).isNull();
            assertThat(column(ids.get(2), "claim_token", UUID.class)).isNull();
        }

        @Test
        void fatalVirtualMachineErrorsStillEscape() {
            publisher.failures.put("test.oom", new OutOfMemoryError("simulated"));
            append("test.oom");

            assertThatThrownBy(() -> relay.runOnce()).isInstanceOf(OutOfMemoryError.class);
        }
    }

    // ---- a retry policy that throws: the relay falls back to retrying after backoff.max ----

    @TestConfiguration
    static class BrokenPolicyConfig {
        @Bean
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }

        @Bean
        RetryPolicy brokenRetryPolicy() {
            return (record, failure, now) -> {
                throw new IllegalStateException("retry policy bug");
            };
        }
    }

    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = OutboxTestApplication.class, properties = "rbac.outbox.enabled=false")
    @Import(BrokenPolicyConfig.class)
    class BrokenRetryPolicy {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registerDatabase(registry);
        }

        @Autowired
        private JsonMapper json;
        @Autowired
        private OutboxRelay brokenRelay;
        @Autowired
        private OutboxWriter brokenWriter;
        @Autowired
        private TenantScope brokenScope;
        @Autowired
        private RecordingPublisher brokenPublisher;
        @Autowired
        private OutboxProperties properties;

        @Test
        void aThrowingRetryPolicyFallsBackToTheMaximumBackoff() {
            brokenPublisher.reset();
            brokenPublisher.failures.put("test.fail", new IllegalStateException("downstream unavailable"));
            List<UUID> ids = append(brokenScope, brokenWriter, json, tenant, "test.ok", "test.fail", "test.ok");

            Instant t0 = Instant.now();
            assertThat(brokenRelay.runOnce()).isEqualTo(3);
            Instant t1 = Instant.now();

            assertThat(exists(ids.get(0))).isFalse();
            assertThat(exists(ids.get(2))).isFalse();
            UUID failed = ids.get(1);
            assertThat(status(failed)).isEqualTo("pending");
            assertThat(column(failed, "claim_token", UUID.class)).isNull();
            assertThat(column(failed, "last_error", String.class))
                    .isEqualTo("java.lang.IllegalStateException: downstream unavailable");
            Duration max = properties.backoff().max();
            Instant next = column(failed, "next_attempt_at", OffsetDateTime.class).toInstant();
            assertThat(next).isBetween(t0.truncatedTo(ChronoUnit.MICROS).plus(max), t1.plus(1, ChronoUnit.MICROS).plus(max));
        }
    }

    // ---- in-process publishing: the relay hands events to the service's own EventHandler beans ----

    static final class RecordingHandler implements EventHandler {
        final Set<String> types;
        final List<EventEnvelope> received = new CopyOnWriteArrayList<>();
        final Consumer<EventEnvelope> action;

        RecordingHandler(Set<String> types, Consumer<EventEnvelope> action) {
            this.types = types;
            this.action = action;
        }

        @Override
        public Set<String> eventTypes() {
            return types;
        }

        @Override
        public void handle(EventEnvelope event) {
            received.add(event);
            action.accept(event);
        }
    }

    @TestConfiguration
    static class HandlersConfig {
        /** Renames the user named in the payload, in the event's tenant, as a real handler that needs the DB would. */
        @Bean
        RecordingHandler userUpdatedHandler(TenantScope tenantScope, JdbcClient jdbc) {
            return new RecordingHandler(Set.of("user.updated"), event -> {
                if (!event.payload().has("userId")) {
                    return;
                }
                int updated = tenantScope.call(event.tenantId(), () -> jdbc
                        .sql("update identity.users set display_name = ? where id = ?")
                        .param(event.payload().get("displayName").asString())
                        .param(UUID.fromString(event.payload().get("userId").asString()))
                        .update());
                if (updated != 1) {
                    throw new IllegalStateException("user not found in the event's tenant");
                }
            });
        }

        @Bean
        RecordingHandler everythingHandler() {
            return new RecordingHandler(Set.of(EventHandler.ANY), event -> {
            });
        }
    }

    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = OutboxTestApplication.class,
            properties = {"rbac.outbox.enabled=false", "rbac.outbox.publisher=in-process"})
    @Import(HandlersConfig.class)
    class InProcess {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registerDatabase(registry);
        }

        @Autowired
        private JsonMapper json;

        @Autowired
        private OutboxRelay inProcessRelay;
        @Autowired
        private OutboxWriter inProcessWriter;
        @Autowired
        private TenantScope inProcessScope;
        @Autowired
        private RecordingHandler userUpdatedHandler;
        @Autowired
        private RecordingHandler everythingHandler;

        @BeforeEach
        void resetHandlers() {
            userUpdatedHandler.received.clear();
            everythingHandler.received.clear();
        }

        @Test
        void inProcessPublisherReachesEveryHandler() {
            UUID id = append(inProcessScope, inProcessWriter, json, tenant, "user.updated").get(0);

            assertThat(inProcessRelay.runOnce()).isEqualTo(1);

            assertThat(userUpdatedHandler.received).extracting(EventEnvelope::id).containsExactly(id);
            assertThat(everythingHandler.received).extracting(EventEnvelope::id).containsExactly(id);
            assertThat(exists(id)).isFalse();
        }

        @Test
        void handlerCanUseTenantScope() {
            UUID user = db.insertUser(tenant, "relay-" + UUID.randomUUID() + "@a.test");
            inProcessScope.call(tenant, () -> {
                inProcessWriter.append(OutboxMessage.of("user", user.toString(), "user.updated",
                        json.readTree("{\"userId\":\"" + user + "\",\"displayName\":\"Renamed by the relay\"}")));
                return null;
            });

            assertThat(inProcessRelay.runOnce()).isEqualTo(1);

            String displayName = db.inTx(null, null, c -> Sql.one(c, String.class,
                    "select display_name from identity.users where id = ?", user));
            assertThat(displayName).isEqualTo("Renamed by the relay");
            assertThat(rows()).as("delivered, so deleted").isZero();
        }
    }

    // ---- a started relay: each test gets a fresh context, closed after it ----

    /** Counts the waits the relay enters, so a test can tell the relay is idle before it writes. */
    static final class CountingWakeup extends OutboxWakeup {
        final AtomicInteger waits = new AtomicInteger();

        @Override
        public void await(Duration max) throws InterruptedException {
            waits.incrementAndGet();
            super.await(max);
        }
    }

    @TestConfiguration
    static class LiveConfig {
        @Bean
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }

        @Bean
        CountingWakeup outboxWakeup() {
            return new CountingWakeup();
        }

        @Bean
        Trace timeline() {
            return new Trace();
        }
    }

    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = OutboxTestApplication.class,
            properties = {"rbac.outbox.enabled=true", "rbac.outbox.poll-interval=30s"})
    @Import(LiveConfig.class)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
    class Running {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registerDatabase(registry);
        }

        @Autowired
        private JsonMapper json;

        @Autowired
        private OutboxRelay liveRelay;
        @Autowired
        private OutboxWriter liveWriter;
        @Autowired
        private TenantScope liveScope;
        @Autowired
        private RecordingPublisher livePublisher;
        @Autowired
        private CountingWakeup liveWakeup;
        @Autowired
        private Trace timeline;

        private Thread relayThread() {
            return Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().equals("outbox-relay-identity"))
                    .findFirst().orElse(null);
        }

        @Test
        void commitWakesTheRunningRelay() {
            assertThat(liveRelay.isRunning()).isTrue();
            Thread thread = relayThread();
            assertThat(thread).isNotNull();
            assertThat(thread.isDaemon()).isTrue();
            // Wait until the relay sleeps on its 30s poll, so only the commit can wake it in time.
            await().atMost(Duration.ofSeconds(5)).until(() -> liveWakeup.waits.get() >= 1);

            String aggregateId = appendForLiveRelay(liveScope, liveWriter, json, tenant, "test.ok");

            await().atMost(Duration.ofSeconds(2)).until(() -> seenFor(livePublisher, aggregateId) != null);
            UUID id = seenFor(livePublisher, aggregateId).id();
            await().atMost(Duration.ofSeconds(2)).until(() -> !exists(id));
        }

        @Test
        void stopFinishesTheBatchInFlight() throws InterruptedException {
            Gate gate = new Gate(timeline);
            livePublisher.gates.put("test.block", gate);
            String aggregateId = appendForLiveRelay(liveScope, liveWriter, json, tenant, "test.block");
            assertThat(gate.entered.await(5, TimeUnit.SECONDS)).as("publish started").isTrue();
            UUID id = seenFor(livePublisher, aggregateId).id();
            assertThat(exists(id)).as("in flight, not yet acknowledged").isTrue();

            CountDownLatch stopped = new CountDownLatch(1);
            Thread stopper = new Thread(() -> liveRelay.stop(() -> {
                timeline.entries.add("callback");
                stopped.countDown();
            }));
            stopper.start();

            assertThat(stopped.await(300, TimeUnit.MILLISECONDS)).as("callback waits for the publish").isFalse();
            gate.release.countDown();
            assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
            stopper.join(5000);

            assertThat(timeline.entries).containsExactly("publish returned", "callback");
            assertThat(exists(id)).as("the batch in flight was acknowledged").isFalse();
            assertThat(liveRelay.isRunning()).isFalse();
            await().atMost(Duration.ofSeconds(2)).until(() -> relayThread() == null);
        }
    }

    /** The lease (60 s by default) outlasts the shutdown phase: stop must not wait past the phase. */
    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = OutboxTestApplication.class, properties = {"rbac.outbox.enabled=true",
            "rbac.outbox.poll-interval=30s", "spring.lifecycle.timeout-per-shutdown-phase=1s"})
    @Import(LiveConfig.class)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
    class ShortShutdownPhase {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registerDatabase(registry);
        }

        @Autowired
        private JsonMapper json;

        @Autowired
        private OutboxRelay shortRelay;
        @Autowired
        private OutboxWriter shortWriter;
        @Autowired
        private TenantScope shortScope;
        @Autowired
        private RecordingPublisher shortPublisher;
        @Autowired
        private Trace timeline;

        @Test
        void stopWaitsNoLongerThanTheShutdownPhase() throws InterruptedException {
            Gate gate = new Gate(timeline);
            shortPublisher.gates.put("test.block", gate);
            appendForLiveRelay(shortScope, shortWriter, json, tenant, "test.block");
            assertThat(gate.entered.await(5, TimeUnit.SECONDS)).as("publish started").isTrue();

            try {
                CountDownLatch stopped = new CountDownLatch(1);
                long t0 = System.nanoTime();
                shortRelay.stop(stopped::countDown);

                assertThat(stopped.await(5, TimeUnit.SECONDS)).as("callback within the 1 s phase, not the lease").isTrue();
                assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
                assertThat(timeline.entries).as("the publish is still held").isEmpty();
            } finally {
                gate.release.countDown();
            }
            await().atMost(Duration.ofSeconds(5)).until(() -> !shortRelay.isRunning());
        }
    }

    /** Fails its first claim, as a database outage would, then behaves. */
    static final class FlakyStore extends OutboxStore {
        final AtomicInteger claims = new AtomicInteger();

        FlakyStore(JdbcClient jdbc, JsonMapper json) {
            super(jdbc, json, "identity");
        }

        @Override
        public List<OutboxRecord> claim(int limit, Duration lease, UUID token) {
            if (claims.getAndIncrement() == 0) {
                throw new IllegalStateException("simulated store failure");
            }
            return super.claim(limit, lease, token);
        }
    }

    @TestConfiguration
    static class FlakyConfig {
        @Bean
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }

        @Bean
        FlakyStore outboxStore(JdbcClient jdbc, JsonMapper json) {
            return new FlakyStore(jdbc, json);
        }
    }

    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = OutboxTestApplication.class,
            properties = {"rbac.outbox.enabled=true", "rbac.outbox.poll-interval=200ms"})
    @Import(FlakyConfig.class)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class StoreOutage {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registerDatabase(registry);
        }

        @Autowired
        private JsonMapper json;

        @Autowired
        private OutboxRelay flakyRelay;
        @Autowired
        private FlakyStore flakyStore;
        @Autowired
        private OutboxWriter flakyWriter;
        @Autowired
        private TenantScope flakyScope;
        @Autowired
        private RecordingPublisher flakyPublisher;

        @Test
        void relaySurvivesAStoreFailureAndKeepsPolling() {
            await().atMost(Duration.ofSeconds(3)).until(() -> flakyStore.claims.get() >= 1);
            assertThat(flakyRelay.isRunning()).isTrue();

            String aggregateId = appendForLiveRelay(flakyScope, flakyWriter, json, tenant, "test.ok");

            await().atMost(Duration.ofSeconds(3)).until(() -> seenFor(flakyPublisher, aggregateId) != null);
            assertThat(flakyStore.claims.get()).isGreaterThan(1);
        }
    }
}
