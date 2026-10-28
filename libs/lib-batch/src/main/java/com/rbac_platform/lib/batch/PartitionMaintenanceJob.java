package com.rbac_platform.lib.batch;

import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The job a service with partitioned tables schedules to keep their future monthly partitions in place: one step that
 * calls the service's own maintenance function (V13), which returns how many partitions it created. The count is
 * logged and kept in the step's execution context under {@code created}.
 */
public final class PartitionMaintenanceJob {

    public static final String NAME = "partition-maintenance";

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceJob.class);
    private static final Pattern FUNCTION = Pattern.compile("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*");

    private PartitionMaintenanceJob() {
    }

    /** @param maintenanceFunction schema-qualified function name, e.g. {@code audit.maintain_partitions} */
    public static Job create(JobRepository repository, PlatformTransactionManager tx, JdbcClient jdbc,
                             String maintenanceFunction) {
        if (maintenanceFunction == null || !FUNCTION.matcher(maintenanceFunction).matches()) {
            throw new IllegalArgumentException("maintenanceFunction must match "
                    + "[a-z_][a-z0-9_]*.[a-z_][a-z0-9_]* but was '" + maintenanceFunction + "'");
        }
        String sql = "select " + maintenanceFunction + "()";
        return new JobBuilder(NAME, repository)
                .start(new StepBuilder("partition-maintenance-step", repository).tasklet((contribution, chunk) -> {
                    int created = jdbc.sql(sql).query(Integer.class).single();
                    log.info("Partition maintenance created {} partition(s)", created);
                    contribution.getStepExecution().getExecutionContext().putInt("created", created);
                    return RepeatStatus.FINISHED;
                }, tx).build())
                .build();
    }
}
