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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * V13: a Spring Batch 6.0.6 job repository per service schema (identity, authz, audit), the schedule-slot table the
 * scheduled jobs claim, and the per-service partition maintenance functions.
 */
class BatchRepositorySchemaTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final List<String> SCHEMAS = List.of("identity", "authz", "audit");
    private static final List<String> OBJECTS = List.of(
            "batch_job_instance", "batch_job_execution", "batch_job_execution_params", "batch_step_execution",
            "batch_step_execution_context", "batch_job_execution_context", "batch_step_execution_seq",
            "batch_job_execution_seq", "batch_job_instance_seq", "batch_schedule_slot");
    private static final String PARTITION_NAME_OF_MONTH_PLUS_3 =
            "to_char(date_trunc('month', now() at time zone 'utc') + interval '3 months', 'YYYYMM')";

    private final MigratedDatabase db = MigratedDatabase.get();

    private static List<String> strings(Connection c, String sql, Object... args) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    @Test
    void eachServiceSchemaHasTheSpringBatchTables() {
        db.inTx(null, null, c -> {
            for (String schema : SCHEMAS) {
                for (String object : OBJECTS) {
                    assertThat(one(c, String.class, "select to_regclass(?)::text", schema + "." + object))
                            .as("%s.%s", schema, object).isNotNull();
                }
            }
            assertThat(strings(c, "select column_name from information_schema.columns "
                    + "where table_schema = 'audit' and table_name = 'batch_step_execution' "
                    + "order by ordinal_position")).containsExactly(
                    "step_execution_id", "version", "step_name", "job_execution_id", "create_time", "start_time",
                    "end_time", "status", "commit_count", "read_count", "filter_count", "write_count",
                    "read_skip_count", "write_skip_count", "process_skip_count", "rollback_count", "exit_code",
                    "exit_message", "last_updated");
            return null;
        });
    }

    @Test
    void serviceRoleRunsItsOwnRepositoryOnly() {
        long id = db.inTx("audit_app", null, c -> {
            long next = one(c, Long.class, "select nextval('audit.batch_job_instance_seq')");
            assertThat((int) exec(c, "insert into audit.batch_job_instance (job_instance_id, version, job_name, "
                    + "job_key) values (?, 0, 'probe-job', ?)", next, "key-" + next)).isEqualTo(1);
            assertThat((int) exec(c, "update audit.batch_job_instance set version = 1 where job_instance_id = ?",
                    next)).isEqualTo(1);
            return next;
        });
        assertThat(sqlState(() -> db.inTx("audit_app", null, c ->
                exec(c, "delete from audit.batch_job_instance where job_instance_id = ?", id))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("audit_app", null, c ->
                strings(c, "select * from identity.batch_job_instance")))).isEqualTo(INSUFFICIENT_PRIVILEGE);
        db.inTx(null, null, c -> exec(c, "delete from audit.batch_job_instance where job_instance_id = ?", id));
    }

    @Test
    void scheduleSlotAcceptsTheFirstClaimOnly() {
        String job = "slot-probe-" + System.nanoTime();
        String sql = "insert into authz.batch_schedule_slot (job_name, scheduled_at, claimed_by) "
                + "values (?, '2030-01-01 00:00:00+00', ?) on conflict do nothing";
        try {
            assertThat(db.<Integer>inTx("authz_app", null, c -> exec(c, sql, job, "node-a"))).isEqualTo(1);
            assertThat(db.<Integer>inTx("authz_app", null, c -> exec(c, sql, job, "node-b"))).isEqualTo(0);
        } finally {
            db.inTx(null, null, c -> exec(c, "delete from authz.batch_schedule_slot where job_name = ?", job));
        }
    }

    @Test
    void maintainPartitionsIsOwnedPerService() {
        // The embedded database is shared: clear the (future) month +3 partition, whatever it holds, and let the
        // service recreate it.
        db.inTx(null, null, c -> {
            String partition = one(c, String.class, "select 'audit.events_p' || " + PARTITION_NAME_OF_MONTH_PLUS_3);
            if (one(c, String.class, "select to_regclass(?)::text", partition) != null) {
                exec(c, "delete from " + partition);
                exec(c, "drop table " + partition);
            }
            return null;
        });

        assertThat(db.<Integer>inTx("audit_app", null, c -> one(c, Integer.class, "select audit.maintain_partitions()")))
                .isEqualTo(1);
        db.inTx(null, null, c -> {
            assertThat(one(c, String.class, "select to_regclass('audit.events_p' || "
                    + PARTITION_NAME_OF_MONTH_PLUS_3 + ")::text")).isNotNull();
            return null;
        });
        // Nothing left to create on a second run.
        assertThat(db.<Integer>inTx("audit_app", null, c -> one(c, Integer.class, "select audit.maintain_partitions()")))
                .isZero();

        assertThat(sqlState(() -> db.inTx("audit_app", null, c ->
                one(c, Integer.class, "select authz.maintain_partitions()")))).isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("authz_app", null, c ->
                one(c, Integer.class, "select audit.maintain_partitions()")))).isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("identity_app", null, c ->
                one(c, Integer.class, "select util.ensure_monthly_partitions('audit.events'::regclass, 3)"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    void createBatchRepositoryValidatesItsSchemaAndIsOwnerOnly() {
        assertThat(sqlState(() -> db.inTx(null, null, c ->
                exec(c, "select util.create_batch_repository('no_such_schema', 'audit_app')"))))
                .isEqualTo("22023");
        assertThat(sqlState(() -> db.inTx(null, null, c ->
                exec(c, "select util.create_batch_repository('public', 'audit_app')")))).isEqualTo("22023");
        assertThat(sqlState(() -> db.inTx("audit_app", null, c ->
                exec(c, "select util.create_batch_repository('audit', 'audit_app')")))).isEqualTo(INSUFFICIENT_PRIVILEGE);
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from pg_proc where oid = 'util.create_batch_repository(text,regrole)'"
                    + "::regprocedure and not prosecdef and not has_function_privilege('public', oid, 'EXECUTE')"))
                    .isEqualTo(1);
            return null;
        });
    }
}
