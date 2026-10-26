package com.rbac_platform.db;

import static com.rbac_platform.db.Sql.count;
import static com.rbac_platform.db.Sql.errorMessage;
import static com.rbac_platform.db.Sql.exec;
import static com.rbac_platform.db.Sql.one;
import static com.rbac_platform.db.Sql.sqlState;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Partition maintenance functions. Every test builds its own util.pt_&lt;rand&gt; partitioned table as
 * rbac_owner (the SECURITY DEFINER functions are owned by rbac_owner and can only partition tables it owns).
 */
class PartitionMaintenanceTest {

    private final MigratedDatabase db = MigratedDatabase.get();

    private String t;

    @BeforeEach
    void createTable() {
        t = "pt_" + UUID.randomUUID().toString().substring(0, 8);
        db.inTx("rbac_owner", null, c -> {
            exec(c, "create table util." + t + " (ts timestamptz not null, v int) partition by range (ts)");
            exec(c, "create table util." + t + "_default partition of util." + t + " default");
            return null;
        });
    }

    @AfterEach
    void dropTable() {
        db.inTx("rbac_owner", null, c -> exec(c, "drop table if exists util." + t));
    }

    private String parent() {
        return "util." + t;
    }

    private int ensure(int monthsAhead) {
        return db.inTx(null, null, c -> one(c, Integer.class,
                "select util.ensure_monthly_partitions(?::regclass, ?)", parent(), monthsAhead));
    }

    private boolean create(String monthExpr) {
        return db.inTx(null, null, c -> one(c, Boolean.class,
                "select util.create_monthly_partition(?::regclass, " + monthExpr + ")", parent()));
    }

    private int drop(String keep) {
        return db.inTx(null, null, c -> one(c, Integer.class,
                "select util.drop_partitions_older_than(?::regclass, ?::interval)", parent(), keep));
    }

    /** Current UTC month, shifted by the given number of months, formatted YYYYMM. */
    private String yyyymm(Connection c, int monthShift) {
        return one(c, String.class,
                "select to_char(date_trunc('month', now() at time zone 'UTC') + make_interval(months => ?), 'YYYYMM')",
                monthShift);
    }

    private boolean exists(Connection c, String relname) {
        return count(c, "select count(*) from pg_class where relnamespace = 'util'::regnamespace and relname = ?",
                relname) == 1;
    }

    @Test
    void ensureCreatesCurrentAndFutureMonthsIdempotently() {
        assertThat(ensure(3)).isEqualTo(4);
        assertThat(ensure(3)).isZero();
        db.inTx(null, null, c -> {
            for (int i = 0; i <= 3; i++) {
                assertThat(exists(c, t + "_p" + yyyymm(c, i))).as("month +%d", i).isTrue();
            }
            assertThat(exists(c, t + "_p" + yyyymm(c, 4))).isFalse();
            assertThat(count(c, "select count(*) from pg_inherits where inhparent = ?::regclass", parent()))
                    .isEqualTo(5); // 4 months + default
            return null;
        });
    }

    @Test
    void createReturnsFalseWhenPartitionAlreadyExists() {
        assertThat(create("date '2031-02-14'")).isTrue();
        assertThat(create("date '2031-02-01'")).isFalse();
    }

    @Test
    void movesRowsOutOfDefaultBeforeAttaching() {
        db.inTx(null, null, c -> {
            // next month start + 1 day lands in DEFAULT because no partition exists yet;
            // +5 months is outside everything ensure(1) creates and must stay in DEFAULT.
            exec(c, "insert into " + parent() + " (ts, v) values "
                    + "(date_trunc('month', now() at time zone 'UTC') at time zone 'UTC' + interval '1 month 1 day', 1),"
                    + "(date_trunc('month', now() at time zone 'UTC') at time zone 'UTC' + interval '5 months 1 day', 2)");
            assertThat(count(c, "select count(*) from " + parent() + "_default")).isEqualTo(2);
            return null;
        });

        assertThat(ensure(1)).isEqualTo(2);

        db.inTx(null, null, c -> {
            String next = yyyymm(c, 1);
            assertThat(count(c, "select count(*) from " + parent() + "_default")).isEqualTo(1);
            assertThat(count(c, "select count(*) from " + parent() + "_default where v = 2")).isEqualTo(1);
            assertThat(count(c, "select count(*) from " + parent() + "_p" + next)).isEqualTo(1);
            assertThat(count(c, "select count(*) from " + parent() + "_p" + next + " where v = 1")).isEqualTo(1);
            assertThat(count(c, "select count(*) from " + parent())).isEqualTo(2);
            return null;
        });
    }

    @Test
    void dropsOnlyPartitionsOlderThanKeep() {
        db.inTx(null, null, c -> {
            assertThat(one(c, Boolean.class, "select util.create_monthly_partition(?::regclass, "
                    + "(date_trunc('month', now() at time zone 'UTC') - interval '14 months')::date)", parent()))
                    .isTrue();
            return null;
        });
        assertThat(ensure(0)).isEqualTo(1);

        assertThat(drop("12 months")).isEqualTo(1);

        db.inTx(null, null, c -> {
            assertThat(exists(c, t + "_p" + yyyymm(c, -14))).isFalse();
            assertThat(exists(c, t + "_p" + yyyymm(c, 0))).isTrue();
            assertThat(exists(c, t + "_default")).isTrue();
            return null;
        });
        assertThat(drop("12 months")).isZero();
    }

    @Test
    void dropDecidesByBoundsNotByName() {
        db.inTx("rbac_owner", null, c -> {
            // Misleading names: an ancient name over future bounds, and a non-conforming name over ancient bounds.
            exec(c, "create table " + parent() + "_p199001 partition of " + parent()
                    + " for values from ('2090-01-01 00:00:00+00') to ('2090-02-01 00:00:00+00')");
            exec(c, "create table " + parent() + "_legacy partition of " + parent()
                    + " for values from ('2001-01-01 00:00:00+00') to ('2001-02-01 00:00:00+00')");
            return null;
        });

        assertThat(drop("1 month")).isEqualTo(1);

        db.inTx(null, null, c -> {
            assertThat(exists(c, t + "_p199001")).isTrue();
            assertThat(exists(c, t + "_legacy")).isFalse();
            assertThat(exists(c, t + "_default")).isTrue();
            return null;
        });
    }

    @Test
    void neverDropsDefaultPartitionEvenWithRows() {
        db.inTx(null, null, c -> {
            exec(c, "insert into " + parent() + " (ts, v) values (timestamptz '2001-01-01 00:00:00+00', 1)");
            return null;
        });
        assertThat(drop("0 seconds")).isZero();
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from " + parent() + "_default")).isEqualTo(1);
            return null;
        });
    }

    @Test
    void monthBoundariesAreUtcRegardlessOfSessionTimeZone() {
        db.inTx(null, null, c -> {
            exec(c, "set local time zone 'Asia/Ho_Chi_Minh'");
            // Mid-month date: a session-zone implementation would start the month at 2031-03-31 17:00 UTC.
            assertThat(one(c, Boolean.class,
                    "select util.create_monthly_partition(?::regclass, date '2031-04-15')", parent())).isTrue();
            exec(c, "set local time zone 'UTC'");
            assertThat(exists(c, t + "_p203104")).isTrue();
            assertThat(exists(c, t + "_p203103")).isFalse();
            assertThat(one(c, String.class,
                    "select pg_get_expr(relpartbound, oid) from pg_class where oid = ?::regclass",
                    parent() + "_p203104"))
                    .isEqualTo("FOR VALUES FROM ('2031-04-01 00:00:00+00') TO ('2031-05-01 00:00:00+00')");
            return null;
        });
    }

    @Test
    void ensureAndDropAreStableUnderNonUtcSessionTimeZone() {
        db.inTx(null, null, c -> {
            exec(c, "set local time zone 'Pacific/Kiritimati'"); // UTC+14
            assertThat(one(c, Integer.class, "select util.ensure_monthly_partitions(?::regclass, 2)", parent()))
                    .isEqualTo(3);
            exec(c, "set local time zone 'UTC'");
            for (int i = 0; i <= 2; i++) {
                assertThat(exists(c, t + "_p" + yyyymm(c, i))).as("month +%d", i).isTrue();
            }
            assertThat(exists(c, t + "_p" + yyyymm(c, 3))).isFalse();
            return null;
        });
    }

    @Test
    void adminRoleCanRunPartitionMaintenance() {
        db.inTx("rbac_admin", null, c -> {
            assertThat(one(c, Integer.class, "select util.ensure_monthly_partitions(?::regclass, 1)", parent()))
                    .isEqualTo(2);
            assertThat(one(c, Integer.class,
                    "select util.drop_partitions_older_than(?::regclass, interval '12 months')", parent()))
                    .isZero();
            return null;
        });
    }

    @Test
    void appRoleCannotRunPartitionMaintenance() {
        String[] calls = {
            "select util.ensure_monthly_partitions(?::regclass, 1)",
            "select util.create_monthly_partition(?::regclass, current_date)",
            "select util.drop_partitions_older_than(?::regclass, interval '12 months')",
        };
        for (String call : calls) {
            assertThat(sqlState(() -> db.inTx("rbac_app", null, c -> one(c, Object.class, call, parent()))))
                    .as(call).isEqualTo("42501");
        }
    }

    @Test
    void rejectsTablesThatAreNotSingleColumnRangePartitioned() {
        String list = "pl_" + UUID.randomUUID().toString().substring(0, 8);
        String multi = "pm_" + UUID.randomUUID().toString().substring(0, 8);
        String plain = "pp_" + UUID.randomUUID().toString().substring(0, 8);
        db.inTx("rbac_owner", null, c -> {
            exec(c, "create table util." + list + " (ts timestamptz not null) partition by list (ts)");
            exec(c, "create table util." + multi + " (ts timestamptz not null, v int not null) "
                    + "partition by range (ts, v)");
            exec(c, "create table util." + plain + " (ts timestamptz not null)");
            return null;
        });
        try {
            for (String name : new String[] {list, multi, plain}) {
                String msg = errorMessage(() -> db.inTx(null, null, c -> one(c, Object.class,
                        "select util.create_monthly_partition(?::regclass, current_date)", "util." + name)));
                assertThat(msg).as(name).contains("partition_unsupported");
            }
        } finally {
            db.inTx("rbac_owner", null, c -> {
                exec(c, "drop table util." + list);
                exec(c, "drop table util." + multi);
                exec(c, "drop table util." + plain);
                return null;
            });
        }
    }

    @Test
    void newPartitionsCarryNoGrants() {
        ensure(0);
        db.inTx(null, null, c -> {
            String child = parent() + "_p" + yyyymm(c, 0);
            assertThat(one(c, Boolean.class, "select has_table_privilege('rbac_app', ?::regclass, 'SELECT')", child))
                    .isFalse();
            assertThat(one(c, Boolean.class, "select has_table_privilege('rbac_admin', ?::regclass, 'SELECT')", child))
                    .isFalse();
            return null;
        });
    }

    @Test
    void doesNotEscalateParentLockWhenDefaultExists() throws SQLException {
        // A concurrent reader/writer holds ACCESS SHARE on the parent. With a DEFAULT partition the
        // function must only need SHARE UPDATE EXCLUSIVE on the parent (ATTACH path), never ACCESS
        // EXCLUSIVE, otherwise it deadlocks/blocks against such queries.
        try (Connection a = db.dataSource().getConnection(); Connection b = db.dataSource().getConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            try {
                exec(a, "lock table only " + parent() + " in access share mode");
                exec(b, "set local lock_timeout = '2s'");
                assertThat(one(b, Boolean.class,
                        "select util.create_monthly_partition(?::regclass, date '2031-07-10')", parent()))
                        .isTrue();
                b.commit();
            } finally {
                a.rollback();
                b.rollback();
            }
        }
        db.inTx(null, null, c -> {
            assertThat(exists(c, t + "_p203107")).isTrue();
            return null;
        });
    }
}
