package com.rbac_platform.db;

import static com.rbac_platform.testsupport.Sql.count;
import static com.rbac_platform.testsupport.Sql.exec;
import static com.rbac_platform.testsupport.Sql.one;
import static com.rbac_platform.testsupport.Sql.sqlState;
import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.testsupport.MigratedDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V12: the per-service transactional outbox (identity.outbox, authz.outbox). The service role may only INSERT
 * into its own tenant; claiming, completing and rescheduling go through SECURITY DEFINER functions and
 * operators list and requeue dead rows. Claims see every tenant, so each test starts and ends with empty tables.
 */
class OutboxSchemaTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String INVALID_PARAMETER_VALUE = "22023";

    private static final String INSERT_ROW = "insert into identity.outbox "
            + "(tenant_id, aggregate_type, aggregate_id, event_type, payload) values (?, 'user', 'u-1', ?, '{}')";

    private final MigratedDatabase db = MigratedDatabase.get();

    @BeforeEach
    @AfterEach
    void emptyOutboxes() {
        db.inTx(null, null, c -> {
            exec(c, "delete from identity.outbox");
            exec(c, "delete from authz.outbox");
            return null;
        });
    }

    /** Inserts one pending row the way the service does: as identity_app, inside the tenant. */
    private void publish(UUID tenant, String eventType) {
        db.inTx("identity_app", tenant, c -> exec(c, INSERT_ROW, tenant, eventType));
    }

    private List<UUID> claim(Connection c, int limit, String lease, UUID token) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select id from identity.outbox_claim(?, ?::interval, ?)")) {
            ps.setInt(1, limit);
            ps.setString(2, lease);
            ps.setObject(3, token);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                }
            }
        }
        return ids;
    }

    /** One claim in its own committed transaction as identity_app, outside any tenant (the relay's view). */
    private List<UUID> claimAsRelay(int limit, String lease, UUID token) {
        return db.<List<UUID>>inTx("identity_app", null, c -> claim(c, limit, lease, token));
    }

    /** A count over the table as superuser. */
    private long rows(String sql, Object... args) {
        return db.<Long>inTx(null, null, c -> count(c, sql, args));
    }

    private UUID idOf(String eventType) {
        return db.inTx(null, null, c -> one(c, UUID.class,
                "select id from identity.outbox where event_type = ?", eventType));
    }

    @Test
    void serviceRoleInsertsOnlyIntoItsTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();

        publish(a, "own");
        assertThat(rows("select count(*) from identity.outbox where tenant_id = ?", a))
                .isEqualTo(1);

        assertThat(sqlState(() -> db.inTx("identity_app", a, c -> exec(c, INSERT_ROW, b, "foreign"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        // Nothing set: tenant_id is given but does not match the (absent) tenant context.
        assertThat(sqlState(() -> db.inTx("identity_app", null, c -> exec(c, INSERT_ROW, a, "none"))))
                .isIn("23502", INSUFFICIENT_PRIVILEGE);
        // Omitting tenant_id entirely is a NOT NULL violation or an RLS violation, never a row.
        assertThat(sqlState(() -> db.inTx("identity_app", a, c -> exec(c, "insert into identity.outbox "
                + "(aggregate_type, aggregate_id, event_type, payload) values ('user', 'u', 'x', '{}')"))))
                .isIn("23502", INSUFFICIENT_PRIVILEGE);
    }

    @Test
    void serviceRoleCannotReadUpdateOrDeleteTheTable() {
        UUID a = db.insertTenant();
        publish(a, "e");
        assertThat(sqlState(() -> db.inTx("identity_app", a, c -> count(c, "select count(*) from identity.outbox"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("identity_app", a, c -> exec(c, "update identity.outbox set attempts = 9"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("identity_app", a, c -> exec(c, "delete from identity.outbox"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        // No RETURNING either: that would need SELECT.
        assertThat(sqlState(() -> db.inTx("identity_app", a, c -> exec(c, INSERT_ROW + " returning id", a, "r"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    void otherServiceRolesCannotTouchTheOutboxOrItsFunctions() {
        UUID a = db.insertTenant();
        for (String role : List.of("authz_app", "audit_app")) {
            assertThat(sqlState(() -> db.inTx(role, a, c -> exec(c, INSERT_ROW, a, "x")))).as(role + " insert")
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
            assertThat(sqlState(() -> db.inTx(role, a, c -> claim(c, 1, "1 second", UUID.randomUUID()))))
                    .as(role + " claim").isEqualTo(INSUFFICIENT_PRIVILEGE);
        }
        assertThat(sqlState(() -> db.inTx("identity_app", a, c -> exec(c, "insert into authz.outbox "
                + "(tenant_id, aggregate_type, aggregate_id, event_type, payload) values (?, 'x', 'x', 'x', '{}')", a))))
                .as("identity_app insert into authz.outbox").isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("identity_app", a, c -> exec(c,
                "select * from authz.outbox_claim(1, interval '1 second', ?)", UUID.randomUUID()))))
                .as("identity_app claim on authz").isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    void claimSeesEveryTenantInOrderAndLeasesTheRows() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        publish(a, "first");
        publish(b, "second");
        UUID tok = UUID.randomUUID();

        db.inTx("identity_app", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("select tenant_id, attempts, claim_token, "
                    + "next_attempt_at > now() as leased from identity.outbox_claim(10, '30 seconds', ?) "
                    + "order by tenant_id")) {
                ps.setObject(1, tok);
                Set<UUID> tenants = new HashSet<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tenants.add(rs.getObject("tenant_id", UUID.class));
                        assertThat(rs.getInt("attempts")).isEqualTo(1);
                        assertThat(rs.getObject("claim_token", UUID.class)).isEqualTo(tok);
                        assertThat(rs.getBoolean("leased")).isTrue();
                    }
                }
                assertThat(tenants).containsExactlyInAnyOrder(a, b);
            }
            return null;
        });
        // Leased: a second claim right after gets nothing.
        assertThat(claimAsRelay(10, "30 seconds", UUID.randomUUID())).isEmpty();
    }

    @Test
    void claimTakesRowsInNextAttemptOrder() {
        UUID a = db.insertTenant();
        publish(a, "newer");
        publish(a, "older");
        db.inTx(null, null, c -> exec(c, "update identity.outbox set next_attempt_at = now() - interval '1 hour' "
                + "where event_type = 'older'"));
        UUID older = idOf("older");

        assertThat(claimAsRelay(1, "30 seconds", UUID.randomUUID()))
                .containsExactly(older);
    }

    @Test
    void concurrentClaimsNeverReturnTheSameRow() throws Exception {
        UUID a = db.insertTenant();
        for (int i = 0; i < 10; i++) {
            publish(a, "e" + i);
        }
        try (Connection c1 = db.dataSource().getConnection(); Connection c2 = db.dataSource().getConnection()) {
            c1.setAutoCommit(false);
            c2.setAutoCommit(false);
            try {
                for (Connection c : List.of(c1, c2)) {
                    try (Statement s = c.createStatement()) {
                        s.execute("SET LOCAL ROLE identity_app");
                    }
                }
                // Both claim before either commits: SKIP LOCKED must hand out disjoint rows.
                List<UUID> first = claim(c1, 5, "30 seconds", UUID.randomUUID());
                List<UUID> second = claim(c2, 5, "30 seconds", UUID.randomUUID());
                c1.commit();
                c2.commit();

                Set<UUID> union = new HashSet<>(first);
                union.addAll(second);
                assertThat(first).hasSize(5);
                assertThat(second).hasSize(5);
                assertThat(union).hasSize(10);
            } finally {
                c1.rollback();
                c2.rollback();
            }
        }
    }

    @Test
    void completeAndRescheduleRequireTheCurrentToken() {
        UUID a = db.insertTenant();
        publish(a, "e");
        UUID id = idOf("e");
        UUID tok = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        assertThat(claimAsRelay(10, "30 seconds", tok)).containsExactly(id);

        db.inTx("identity_app", null, c -> {
            assertThat(one(c, Integer.class, "select identity.outbox_complete(?, ?)", stale,
                    c.createArrayOf("uuid", new UUID[] {id}))).isZero();
            assertThat(one(c, Boolean.class, "select identity.outbox_reschedule(?, ?, 'boom', null, false)",
                    stale, id)).isFalse();
            return null;
        });
        assertThat(rows("select count(*) from identity.outbox where id = ?", id))
                .isEqualTo(1);

        db.inTx("identity_app", null, c -> {
            assertThat(one(c, Boolean.class, "select identity.outbox_reschedule(?, ?, 'boom', null, false)",
                    tok, id)).isTrue();
            return null;
        });
        db.inTx(null, null, c -> {
            assertThat(one(c, String.class, "select status from identity.outbox where id = ?", id)).isEqualTo("pending");
            assertThat(one(c, UUID.class, "select claim_token from identity.outbox where id = ?", id)).isNull();
            assertThat(one(c, String.class, "select last_error from identity.outbox where id = ?", id))
                    .isEqualTo("boom");
            return null;
        });

        // The reschedule released the claim; a new claim gets a new token and only that token completes the row.
        UUID tok2 = UUID.randomUUID();
        assertThat(claimAsRelay(10, "30 seconds", tok2)).containsExactly(id);
        assertThat(db.<Integer>inTx("identity_app", null, c -> one(c, Integer.class, "select identity.outbox_complete(?, ?)",
                tok2, c.createArrayOf("uuid", new UUID[] {id})))).isEqualTo(1);
        assertThat(rows("select count(*) from identity.outbox where id = ?", id))
                .isZero();
    }

    @Test
    void rescheduleCanBuryAndAdminCanListAndRequeue() {
        UUID a = db.insertTenant();
        publish(a, "e");
        UUID id = idOf("e");
        UUID tok = UUID.randomUUID();
        claimAsRelay(10, "30 seconds", tok);

        assertThat(db.<Boolean>inTx("identity_app", null, c -> one(c, Boolean.class,
                "select identity.outbox_reschedule(?, ?, ?, null, true)", tok, id, "x".repeat(3000)))).isTrue();
        db.inTx(null, null, c -> {
            assertThat(one(c, String.class, "select status from identity.outbox where id = ?", id)).isEqualTo("dead");
            assertThat(one(c, Integer.class, "select length(last_error) from identity.outbox where id = ?", id))
                    .isEqualTo(2000);
            return null;
        });
        // Dead rows are never claimed again.
        assertThat(claimAsRelay(10, "30 seconds", UUID.randomUUID())).isEmpty();

        db.inTx("rbac_admin", null, c -> {
            assertThat(one(c, UUID.class, "select id from identity.outbox_dead(10)")).isEqualTo(id);
            assertThat(one(c, Integer.class, "select identity.outbox_requeue(?)",
                    c.createArrayOf("uuid", new UUID[] {id}))).isEqualTo(1);
            return null;
        });
        db.inTx(null, null, c -> {
            assertThat(one(c, String.class, "select status from identity.outbox where id = ?", id)).isEqualTo("pending");
            assertThat(one(c, Integer.class, "select attempts from identity.outbox where id = ?", id)).isZero();
            assertThat(one(c, String.class, "select last_error from identity.outbox where id = ?", id)).isNull();
            return null;
        });
        assertThat(sqlState(() -> db.inTx("identity_app", null, c -> exec(c, "select * from identity.outbox_dead(10)"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("identity_app", null, c -> exec(c, "select identity.outbox_requeue(?)",
                c.createArrayOf("uuid", new UUID[] {id})))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    void claimRejectsInvalidArguments() {
        UUID tok = UUID.randomUUID();
        for (int limit : new int[] {0, 1001}) {
            assertThat(sqlState(() -> db.inTx("identity_app", null, c -> claim(c, limit, "30 seconds", tok))))
                    .as("limit " + limit).isEqualTo(INVALID_PARAMETER_VALUE);
        }
        assertThat(sqlState(() -> db.inTx("identity_app", null, c -> claim(c, 10, "0 seconds", tok))))
                .as("zero lease").isEqualTo(INVALID_PARAMETER_VALUE);
        assertThat(sqlState(() -> claimAsRelay(10, "30 seconds", null)))
                .as("null token").isEqualTo(INVALID_PARAMETER_VALUE);
        for (int limit : new int[] {0, 1001}) {
            assertThat(sqlState(() -> db.inTx("rbac_admin", null, c -> exec(c, "select * from identity.outbox_dead(?)", limit))))
                    .as("dead limit " + limit).isEqualTo(INVALID_PARAMETER_VALUE);
        }
    }

    @Test
    void outboxPoliciesAreExactlyInsertForTheRoleAndOwnerAll() {
        for (String s : List.of("identity", "authz")) {
            String role = s + "_app";
            db.inTx(null, null, c -> {
                List<String> policies = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("select policyname || ' ' || cmd || ' ' || roles::text "
                        + "from pg_policies where schemaname = ? and tablename = 'outbox'")) {
                    ps.setString(1, s);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            policies.add(rs.getString(1));
                        }
                    }
                }
                assertThat(policies).as(s).containsExactlyInAnyOrder(
                        "tenant_isolation INSERT {" + role + "}", "owner_all ALL {rbac_owner}");
                assertThat(one(c, Boolean.class, "select relrowsecurity and relforcerowsecurity from pg_class "
                        + "where oid = (? || '.outbox')::regclass", s)).as(s).isTrue();
                return null;
            });
        }
    }

    @Test
    void pendingIndexIsUsedByTheClaimQuery() {
        try {
            db.inTx(null, null, c -> {
                exec(c, "insert into identity.outbox (tenant_id, aggregate_type, aggregate_id, event_type, payload, "
                        + "next_attempt_at) select gen_random_uuid(), 'user', i::text, 'bulk', '{}', "
                        + "now() + interval '1 day' from generate_series(1, 2000) i");
                exec(c, "analyze identity.outbox");
                return null;
            });
            String plan = db.<String>inTx(null, null, c -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("explain (format json) "
                        + "select id from identity.outbox where status = 'pending' and next_attempt_at <= now() "
                        + "order by next_attempt_at, id limit 100")) {
                    rs.next();
                    return rs.getString(1);
                }
            });
            assertThat(plan).contains("outbox_pending_idx");
        } finally {
            emptyOutboxes();
        }
    }

    @Test
    void createOutboxRejectsAForeignSchema() {
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c, "select util.create_outbox('public', 'identity_app')"))))
                .isEqualTo(INVALID_PARAMETER_VALUE);
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c, "select util.create_outbox('nope', 'identity_app')"))))
                .isEqualTo(INVALID_PARAMETER_VALUE);
    }
}
