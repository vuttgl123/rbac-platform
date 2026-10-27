package com.rbac_platform.db;

import static com.rbac_platform.testsupport.Sql.count;
import static com.rbac_platform.testsupport.Sql.exec;
import static com.rbac_platform.testsupport.Sql.one;
import static com.rbac_platform.testsupport.Sql.sqlState;
import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.testsupport.MigratedDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** audit.events: append-only for rbac_app, tenant-isolated, monthly partitions with a DEFAULT safety net. */
class AuditTest {

    private static final String INSERT_EVENT =
            "insert into audit.events (tenant_id, actor_type, actor_id, action, target_type, target_id, outcome, "
                    + "ip, user_agent, request_id, metadata) "
                    + "values (?, 'user', 'u-1', 'user.login.succeeded', 'user', 'u-1', 'success', "
                    + "'203.0.113.7'::inet, 'JUnit', 'req-1', '{\"k\":\"v\"}'::jsonb)";

    private final MigratedDatabase db = MigratedDatabase.get();

    @Test
    void appRoleCanInsertAndReadOwnEvents() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        db.inTx("rbac_app", a, c -> exec(c, INSERT_EVENT, a));
        db.inTx("rbac_app", b, c -> exec(c, INSERT_EVENT, b));

        db.inTx("rbac_app", a, c -> {
            assertThat(count(c, "select count(*) from audit.events")).isEqualTo(1);
            assertThat(one(c, String.class, "select action from audit.events")).isEqualTo("user.login.succeeded");
            assertThat(one(c, String.class, "select metadata ->> 'k' from audit.events")).isEqualTo("v");
            assertThat(one(c, UUID.class, "select id from audit.events")).isNotNull();
            return null;
        });
        // Another tenant's context cannot insert a row for tenant A either.
        assertThat(sqlState(() -> db.inTx("rbac_app", b, c -> exec(c, INSERT_EVENT, a)))).isEqualTo("42501");
    }

    @Test
    void appRoleCannotUpdateOrDeleteEvents() {
        UUID t = db.insertTenant();
        db.inTx("rbac_app", t, c -> exec(c, INSERT_EVENT, t));

        assertThat(sqlState(() -> db.inTx("rbac_app", t, c ->
                exec(c, "update audit.events set outcome = 'failure'")))).isEqualTo("42501");
        assertThat(sqlState(() -> db.inTx("rbac_app", t, c ->
                exec(c, "delete from audit.events")))).isEqualTo("42501");
        long remaining = db.inTx("rbac_app", t, c -> count(c, "select count(*) from audit.events"));
        assertThat(remaining).isEqualTo(1);
    }

    @Test
    void adminRoleCannotUpdateOrDeleteEventsButRetentionStillWorks() {
        UUID t = db.insertTenant();
        db.inTx("rbac_admin", null, c -> exec(c, INSERT_EVENT, t));

        assertThat(sqlState(() -> db.inTx("rbac_admin", null, c ->
                exec(c, "update audit.events set outcome = 'failure' where tenant_id = ?", t)))).isEqualTo("42501");
        assertThat(sqlState(() -> db.inTx("rbac_admin", null, c ->
                exec(c, "delete from audit.events where tenant_id = ?", t)))).isEqualTo("42501");
        db.inTx("rbac_admin", null, c -> {
            assertThat(count(c, "select count(*) from audit.events where tenant_id = ?", t)).isEqualTo(1);
            // Retention goes through the SECURITY DEFINER partition drop (nothing is that old here).
            assertThat(one(c, Integer.class,
                    "select util.drop_partitions_older_than('audit.events'::regclass, interval '100 years')"))
                    .isZero();
            return null;
        });
    }

    @Test
    void actorTypeAndOutcomeAreChecked() {
        UUID t = db.insertTenant();
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into audit.events (tenant_id, actor_type, action, outcome) values (?, 'robot', 'x.y', 'success')",
                t)))).isEqualTo("23514");
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into audit.events (tenant_id, actor_type, action, outcome) values (?, 'user', 'x.y', 'maybe')",
                t)))).isEqualTo("23514");
    }

    @Test
    void currentMonthEventLandsInMonthlyPartitionNotDefault() {
        UUID t = db.insertTenant();
        db.inTx(null, null, c -> exec(c, INSERT_EVENT, t));
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from audit.events_default where tenant_id = ?", t)).isZero();
            assertThat(count(c, "select count(*) from audit.events where tenant_id = ?", t)).isEqualTo(1);
            return null;
        });
    }

    @Test
    void farFutureEventLandsInDefaultPartition() {
        UUID t = db.insertTenant();
        db.inTx(null, null, c -> exec(c,
                "insert into audit.events (tenant_id, occurred_at, actor_type, action, outcome) "
                        + "values (?, now() + interval '1 year', 'system', 'retention.test', 'success')", t));
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from audit.events_default where tenant_id = ?", t)).isEqualTo(1);
            return null;
        });
    }

    @Test
    void lateMonthIsMovedOutOfDefaultWhenPartitionIsEnsured() {
        UUID t = db.insertTenant();
        // 5 months ahead is beyond the 3 pre-created months, so it lands in the DEFAULT partition first.
        UUID id = db.inTx(null, null, c -> one(c, UUID.class,
                "insert into audit.events (tenant_id, occurred_at, actor_type, action, outcome) "
                        + "values (?, date_trunc('month', now() at time zone 'UTC') + interval '5 months 3 days', "
                        + "'system', 'late.month', 'success') returning id", t));
        long inDefault = db.inTx(null, null, c -> count(c,
                "select count(*) from audit.events_default where id = ?", id));
        assertThat(inDefault).isEqualTo(1);

        db.inTx(null, null, c -> one(c, Integer.class,
                "select util.ensure_monthly_partitions('audit.events'::regclass, 5)"));

        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from audit.events_default where id = ?", id)).isZero();
            String partition = one(c, String.class,
                    "select tableoid::regclass::text from audit.events where id = ?", id);
            assertThat(partition).startsWith("audit.events_p").isNotEqualTo("audit.events_default");
            assertThat(count(c, "select count(*) from audit.events where id = ?", id)).isEqualTo(1);
            return null;
        });
    }
}
