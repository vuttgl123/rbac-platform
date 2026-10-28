package com.rbac_platform.lib.batch;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Batch settings (rbac.batch.*). Bad values fail startup with a message naming the property.
 *
 * @param schema            schema that holds the service's job repository and schedule slots; it is concatenated into
 *                          SQL, so it must be a plain lower-case identifier. Null only while batch is not configured
 *                          (the auto-configuration is then inactive)
 * @param schedulerPoolSize threads that fire scheduled jobs; a job runs on the thread that fired it
 * @param jobs              schedules by job name; a job without an entry is never scheduled, only launched on demand
 */
@ConfigurationProperties("rbac.batch")
public record BatchProperties(String schema,
                              @DefaultValue("2") int schedulerPoolSize,
                              Map<String, JobSchedule> jobs) {

    private static final Pattern SCHEMA = Pattern.compile("[a-z_][a-z0-9_]*");

    /**
     * @param enabled whether this job is scheduled
     * @param cron    Spring cron expression (six fields, seconds first), evaluated in UTC
     */
    public record JobSchedule(@DefaultValue("true") boolean enabled, String cron) {
    }

    public BatchProperties {
        if (schema != null) {
            requireSchema(schema);
        }
        if (schedulerPoolSize < 1) {
            throw new IllegalArgumentException(
                    "rbac.batch.scheduler-pool-size must be at least 1 but was " + schedulerPoolSize);
        }
        jobs = jobs == null ? Map.of() : Map.copyOf(jobs);
    }

    /** The one place the schema name is vetted before it is concatenated into SQL. */
    static String requireSchema(String schema) {
        if (schema == null || !SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException(
                    "rbac.batch.schema must match [a-z_][a-z0-9_]* but was '" + schema + "'");
        }
        return schema;
    }
}
