package com.rbac_platform.lib.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import com.rbac_platform.lib.db.TenantScope;
import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import com.rbac_platform.testsupport.Sql;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = OutboxTestApplication.class)
@Import(OutboxWriterTest.CountingWakeupConfig.class)
class OutboxWriterTest {

    static class CountingWakeup extends OutboxWakeup {
        final AtomicInteger signals = new AtomicInteger();

        @Override
        public void signal() {
            signals.incrementAndGet();
            super.signal();
        }
    }

    @TestConfiguration
    static class CountingWakeupConfig {
        @Bean
        CountingWakeup outboxWakeup() {
            return new CountingWakeup();
        }
    }

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
    private TenantScope tenantScope;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private CountingWakeup wakeup;

    @BeforeEach
    void resetCounter() {
        wakeup.signals.set(0);
    }

    private JsonNode payload(String text) {
        return json.readTree(text);
    }

    private long outboxRows(UUID tenant) {
        return db.inTx(null, null, c -> Sql.count(c, "select count(*) from identity.outbox where tenant_id = ?", tenant));
    }

    private long userRows(UUID tenant) {
        return db.inTx(null, null, c -> Sql.count(c, "select count(*) from identity.users where tenant_id = ?", tenant));
    }

    private void insertUser(UUID tenant, String email) {
        jdbc.sql("insert into identity.users (tenant_id, email, status) values (?, ?, 'active')")
                .param(tenant).param(email).update();
    }

    @Test
    void appendCommitsWithTheBusinessWrite() {
        UUID t = db.insertTenant();
        MDC.put(RequestLoggingFilter.MDC_KEY, "req-1");
        try {
            tenantScope.call(t, () -> {
                insertUser(t, "writer@a.test");
                writer.append(OutboxMessage.of("user", "u-1", "user.created", payload("{\"id\":\"u-1\"}")));
                return null;
            });
        } finally {
            MDC.remove(RequestLoggingFilter.MDC_KEY);
        }

        assertThat(userRows(t)).isEqualTo(1);
        List<Map<String, String>> rows = db.inTx(null, null, c -> {
            try (var ps = c.prepareStatement("""
                    select aggregate_type, aggregate_id, event_type, payload::text, headers ->> 'requestId', status
                    from identity.outbox where tenant_id = ?""")) {
                ps.setObject(1, t);
                try (var rs = ps.executeQuery()) {
                    List<Map<String, String>> out = new java.util.ArrayList<>();
                    while (rs.next()) {
                        out.add(Map.of("agg", rs.getString(1), "aggId", rs.getString(2), "type", rs.getString(3),
                                "payload", rs.getString(4), "req", rs.getString(5), "status", rs.getString(6)));
                    }
                    return out;
                }
            }
        });
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("agg", "user").containsEntry("aggId", "u-1")
                .containsEntry("type", "user.created").containsEntry("req", "req-1")
                .containsEntry("status", "pending");
        assertThat(payload(rows.get(0).get("payload")).get("id").asString()).isEqualTo("u-1");
    }

    @Test
    void messageHeadersWinOverTheRequestIdFromTheLogContext() {
        UUID t = db.insertTenant();
        MDC.put(RequestLoggingFilter.MDC_KEY, "from-mdc");
        try {
            tenantScope.call(t, () -> {
                writer.append(new OutboxMessage("user", "u-1", "user.created", payload("{}"),
                        Map.of("requestId", "from-message", "actor", "alice")));
                return null;
            });
        } finally {
            MDC.remove(RequestLoggingFilter.MDC_KEY);
        }
        String headers = db.inTx(null, null, c -> Sql.one(c, String.class,
                "select headers::text from identity.outbox where tenant_id = ?", t));
        JsonNode node = payload(headers);
        assertThat(node.get("requestId").asString()).isEqualTo("from-message");
        assertThat(node.get("actor").asString()).isEqualTo("alice");
    }

    @Test
    void rollbackLeavesNoEvent() {
        UUID t = db.insertTenant();
        assertThatThrownBy(() -> tenantScope.call(t, () -> {
            insertUser(t, "rolled@back.test");
            writer.append(OutboxMessage.of("user", "u-1", "user.created", payload("{}")));
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(userRows(t)).isZero();
        assertThat(outboxRows(t)).isZero();
    }

    @Test
    void appendAllWritesEveryMessageInOneCall() {
        UUID t = db.insertTenant();
        tenantScope.call(t, () -> {
            writer.appendAll(List.of(
                    OutboxMessage.of("user", "u-1", "user.created", payload("{\"n\":1}")),
                    OutboxMessage.of("user", "u-2", "user.updated", payload("{\"n\":2}")),
                    new OutboxMessage("role", "r-1", "role.created", payload("{\"n\":3}"), Map.of("actor", "bob"))));
            return null;
        });
        assertThat(outboxRows(t)).isEqualTo(3);
        List<String> types = db.inTx(null, null, c -> {
            try (var ps = c.prepareStatement(
                    "select string_agg(event_type || ':' || (payload ->> 'n') || ':' || coalesce(headers ->> 'actor', '-'), ',' order by id) "
                            + "from identity.outbox where tenant_id = ?")) {
                ps.setObject(1, t);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return List.of(rs.getString(1).split(","));
                }
            }
        });
        assertThat(types).containsExactlyInAnyOrder("user.created:1:-", "user.updated:2:-", "role.created:3:bob");
    }

    @Test
    void appendAllWithNoMessagesDoesNothing() {
        UUID t = db.insertTenant();
        tenantScope.call(t, () -> {
            writer.appendAll(List.of());
            return null;
        });
        assertThat(outboxRows(t)).isZero();
        assertThat(wakeup.signals.get()).isZero();
    }

    @Test
    void appendOutsideATransactionFails() {
        assertThatThrownBy(() -> writer.append(OutboxMessage.of("user", "u-1", "user.created", payload("{}"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TenantScope.call");
        assertThatThrownBy(() -> writer.appendAll(List.of(OutboxMessage.of("user", "u-1", "user.created", payload("{}")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TenantScope.call");
    }

    @Test
    void appendWithoutATenantFailsWithAClearMessage() {
        assertThatThrownBy(() -> transactions.execute(status -> {
            writer.append(OutboxMessage.of("user", "u-1", "user.created", payload("{}")));
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("TenantScope.call");
        assertThatThrownBy(() -> transactions.execute(status -> {
            writer.appendAll(List.of(OutboxMessage.of("user", "u-1", "user.created", payload("{}"))));
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("TenantScope.call");
    }

    @Test
    void commitSignalsTheWakeupOncePerTransaction() {
        UUID t = db.insertTenant();
        tenantScope.call(t, () -> {
            writer.append(OutboxMessage.of("user", "u-1", "user.created", payload("{}")));
            writer.appendAll(List.of(OutboxMessage.of("user", "u-2", "user.created", payload("{}"))));
            assertThat(wakeup.signals.get()).as("nothing before the commit").isZero();
            return null;
        });
        assertThat(wakeup.signals.get()).isEqualTo(1);

        tenantScope.call(t, () -> {
            writer.append(OutboxMessage.of("user", "u-3", "user.created", payload("{}")));
            return null;
        });
        assertThat(wakeup.signals.get()).isEqualTo(2);
    }

    @Test
    void rollbackDoesNotSignalTheWakeup() {
        UUID t = db.insertTenant();
        assertThatThrownBy(() -> tenantScope.call(t, () -> {
            writer.append(OutboxMessage.of("user", "u-1", "user.created", payload("{}")));
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(wakeup.signals.get()).isZero();
    }

    @Test
    void messageRejectsMissingFields() {
        JsonNode ok = payload("{}");
        assertThatThrownBy(() -> OutboxMessage.of("user", "u-1", "user.created", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OutboxMessage.of("user", "u-1", " ", ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxMessage.of("", "u-1", "user.created", ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxMessage.of("user", null, "user.created", ok)).isInstanceOf(NullPointerException.class);
        assertThat(OutboxMessage.of("user", "u-1", "user.created", ok).headers()).isEmpty();
        assertThat(new OutboxMessage("user", "u-1", "user.created", ok, null).headers()).isEmpty();
    }
}
