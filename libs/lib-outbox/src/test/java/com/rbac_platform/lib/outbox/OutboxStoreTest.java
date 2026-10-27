package com.rbac_platform.lib.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.lib.db.TenantScope;
import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import com.rbac_platform.testsupport.Sql;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = OutboxTestApplication.class)
class OutboxStoreTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppDatabase.register(registry, "identity_app");
        registry.add("rbac.outbox.schema", () -> "identity");
        registry.add("rbac.outbox.enabled", () -> "false");
    }

    private final MigratedDatabase db = MigratedDatabase.get();

    @Autowired
    private OutboxWriter writer;
    @Autowired
    private OutboxStore store;
    @Autowired
    private TenantScope tenantScope;
    @Autowired
    private JsonMapper json;

    @BeforeEach
    void emptyOutbox() {
        // The claim functions see every tenant, so start from an empty queue.
        db.inTx(null, null, c -> Sql.exec(c, "delete from identity.outbox"));
    }

    private String statusOf(UUID id) {
        return db.inTx(null, null, c -> Sql.one(c, String.class, "select status from identity.outbox where id = ?", id));
    }

    private long rows() {
        return db.inTx(null, null, c -> Sql.count(c, "select count(*) from identity.outbox"));
    }

    @Test
    void claimCompleteAndRescheduleRoundTrip() {
        UUID t = db.insertTenant();
        tenantScope.call(t, () -> {
            writer.appendAll(List.of(
                    new OutboxMessage("user", "u-1", "user.created", json.readTree("{\"n\":1}"), Map.of("actor", "alice")),
                    OutboxMessage.of("user", "u-2", "user.updated", json.readTree("{\"n\":2}")),
                    OutboxMessage.of("user", "u-3", "user.deleted", json.readTree("{\"n\":3}"))));
            return null;
        });

        UUID token = UUID.randomUUID();
        Instant before = Instant.now();
        List<OutboxRecord> claimed = store.claim(10, Duration.ofSeconds(60), token);

        assertThat(claimed).hasSize(3);
        assertThat(claimed).extracting(OutboxRecord::attempts).containsOnly(1);
        assertThat(claimed).extracting(OutboxRecord::tenantId).containsOnly(t);
        assertThat(claimed).extracting(OutboxRecord::createdAt).allSatisfy(at ->
                assertThat(at).isBetween(before.minusSeconds(60), Instant.now().plusSeconds(1)));
        // Sorted by id, which is time-ordered; compare as Postgres does, byte-wise unsigned.
        List<String> ids = claimed.stream().map(r -> r.id().toString()).toList();
        assertThat(ids).isSorted();
        OutboxRecord withHeader = claimed.stream().filter(r -> r.aggregateId().equals("u-1")).findFirst().orElseThrow();
        assertThat(withHeader.headers()).containsEntry("actor", "alice");
        assertThat(withHeader.payload().get("n").asInt()).isEqualTo(1);
        assertThat(withHeader.toEnvelope().occurredAt()).isEqualTo(withHeader.createdAt());
        assertThat(withHeader.toEnvelope().eventType()).isEqualTo("user.created");

        // Leased rows are not handed out again.
        assertThat(store.claim(10, Duration.ofSeconds(60), UUID.randomUUID())).isEmpty();

        UUID first = claimed.get(0).id();
        UUID second = claimed.get(1).id();
        UUID third = claimed.get(2).id();

        assertThat(store.complete(token, List.of(first))).isEqualTo(1);
        assertThat(rows()).isEqualTo(2);
        assertThat(store.complete(UUID.randomUUID(), List.of(second))).as("stale token").isZero();

        assertThat(store.reschedule(token, second, "failed", Instant.now().plusSeconds(30), true)).isTrue();
        assertThat(statusOf(second)).isEqualTo("dead");
        assertThat(store.reschedule(UUID.randomUUID(), third, "stale", Instant.now(), false)).as("stale token").isFalse();
        assertThat(statusOf(third)).isEqualTo("pending");
    }

    @Test
    void rescheduleWithoutBuryingMakesTheRowDueAgain() {
        UUID t = db.insertTenant();
        tenantScope.call(t, () -> {
            writer.append(OutboxMessage.of("user", "u-1", "user.created", json.readTree("{}")));
            return null;
        });
        UUID token = UUID.randomUUID();
        OutboxRecord record = store.claim(10, Duration.ofSeconds(60), token).get(0);

        assertThat(store.reschedule(token, record.id(), "transient", Instant.now().minusSeconds(1), false)).isTrue();

        List<OutboxRecord> again = store.claim(10, Duration.ofSeconds(60), UUID.randomUUID());
        assertThat(again).hasSize(1);
        assertThat(again.get(0).attempts()).isEqualTo(2);
    }

    @Test
    void completeWithNoIdsDoesNotCallTheDatabase() {
        JdbcClient jdbc = org.mockito.Mockito.mock(JdbcClient.class);
        OutboxStore offline = new OutboxStore(jdbc, json, "identity");
        assertThat(offline.complete(UUID.randomUUID(), List.of())).isZero();
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void invalidSchemaPropertyFailsStartup() {
        for (String bad : new String[] {"identity;drop", "Identity", "1abc", "a-b", "", "a.b"}) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                    .withBean(JdbcClient.class, () -> org.mockito.Mockito.mock(JdbcClient.class))
                    .withBean(JsonMapper.class, JsonMapper::new)
                    .withPropertyValues("rbac.outbox.schema=" + bad)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context).getFailure().hasStackTraceContaining("rbac.outbox.schema");
                    });
        }
    }

    @Test
    void invalidTuningPropertiesFailStartup() {
        List<String> bad = new ArrayList<>(List.of("rbac.outbox.batch-size=0", "rbac.outbox.batch-size=1001",
                "rbac.outbox.poll-interval=0s", "rbac.outbox.lease=-1s", "rbac.outbox.max-attempts=0",
                "rbac.outbox.backoff.initial=0s", "rbac.outbox.backoff.max=0s"));
        for (String property : bad) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                    .withBean(JdbcClient.class, () -> org.mockito.Mockito.mock(JdbcClient.class))
                    .withBean(JsonMapper.class, JsonMapper::new)
                    .withPropertyValues("rbac.outbox.schema=identity", property)
                    .run(context -> {
                        assertThat(context).as(property).hasFailed();
                        assertThat(context).getFailure().hasStackTraceContaining(property.substring(0, property.indexOf('=')));
                    });
        }
    }

    @Test
    void validDefaultsStartTheBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                .withBean(JdbcClient.class, () -> org.mockito.Mockito.mock(JdbcClient.class))
                .withBean(JsonMapper.class, JsonMapper::new)
                .withPropertyValues("rbac.outbox.schema=identity")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OutboxWriter.class).hasSingleBean(OutboxStore.class)
                            .hasSingleBean(OutboxWakeup.class);
                    OutboxProperties p = context.getBean(OutboxProperties.class);
                    assertThat(p.batchSize()).isEqualTo(100);
                    assertThat(p.pollInterval()).isEqualTo(Duration.ofMillis(500));
                    assertThat(p.backoff().max()).isEqualTo(Duration.ofMinutes(5));
                });
    }

    @Test
    void noSchemaMeansNoOutbox() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(OutboxWriter.class));
    }
}
