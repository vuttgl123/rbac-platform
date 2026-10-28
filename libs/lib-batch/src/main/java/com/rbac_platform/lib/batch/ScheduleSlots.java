package com.rbac_platform.lib.batch;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The multi-instance guard of scheduled jobs: every instance of a service fires the same cron, and only the one whose
 * insert of the (job, scheduled time) slot lands runs the job. Slots are never deleted, so a slot runs at most once
 * even when its run fails.
 */
public class ScheduleSlots {

    private final JdbcClient jdbc;
    private final String claimSql;

    public ScheduleSlots(JdbcClient jdbc, String schema) {
        this.jdbc = jdbc;
        BatchProperties.requireSchema(schema);
        this.claimSql = "insert into " + schema + ".batch_schedule_slot (job_name, scheduled_at, claimed_by) "
                + "values (?, ?, ?) on conflict do nothing";
    }

    /** True when this caller claimed the slot, false when another instance (or an earlier fire) already holds it. */
    public boolean claim(String jobName, Instant scheduledAt, String claimedBy) {
        Objects.requireNonNull(jobName, "jobName");
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        Objects.requireNonNull(claimedBy, "claimedBy");
        return jdbc.sql(claimSql).param(jobName).param(scheduledAt.atOffset(ZoneOffset.UTC)).param(claimedBy)
                .update() == 1;
    }
}
