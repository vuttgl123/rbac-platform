package com.rbac_platform.lib.batch;

import com.rbac_platform.lib.db.DbAutoConfiguration;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Batch for a service that sets rbac.batch.schema; a service without that property gets nothing. Boot's own batch
 * auto-configuration provides the job repository and operator (pointed at the schema by rbac/batch-defaults.yaml);
 * this adds launching by name and the slot-guarded scheduler. The service's jobs are its {@code Job} beans.
 */
@AutoConfiguration(after = DbAutoConfiguration.class, afterName = {
        "org.springframework.boot.batch.jdbc.autoconfigure.BatchJdbcAutoConfiguration",
        "org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration"})
@ConditionalOnProperty("rbac.batch.schema")
@EnableConfigurationProperties(BatchProperties.class)
public class BatchAutoConfiguration {

    static final String DEFAULTS = "classpath:rbac/batch-defaults.yaml";

    /**
     * Without the batch defaults, Boot would launch every job at startup on every instance (bypassing the schedule
     * slots) and keep the job repository in the wrong tables, so a service that sets rbac.batch.schema must import
     * them.
     */
    BatchAutoConfiguration(Environment environment, BatchProperties properties) {
        Binder binder = Binder.get(environment);
        boolean launchesAtStartup = binder.bind("spring.batch.job.enabled", Boolean.class).orElse(true);
        String tablePrefix = binder.bind("spring.batch.jdbc.table-prefix", String.class).orElse(null);
        String expectedPrefix = properties.schema() + ".batch_";
        if (launchesAtStartup || !expectedPrefix.equals(tablePrefix)) {
            throw new IllegalStateException("rbac.batch.schema is set but the batch defaults are missing "
                    + "(spring.batch.job.enabled=" + launchesAtStartup + ", spring.batch.jdbc.table-prefix="
                    + tablePrefix + "; expected false and " + expectedPrefix + "): import " + DEFAULTS
                    + " with spring.config.import");
        }
    }

    @Bean
    @ConditionalOnMissingBean
    ScheduleSlots scheduleSlots(JdbcClient jdbc, BatchProperties properties) {
        return new ScheduleSlots(jdbc, properties.schema());
    }

    @Bean
    @ConditionalOnMissingBean
    JobLaunchService jobLaunchService(JobOperator operator, JobRepository repository, ObjectProvider<Job> jobs) {
        return new JobLaunchService(operator, repository, jobs.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    ScheduledJobRunner scheduledJobRunner(BatchProperties properties, JobLaunchService launcher, ScheduleSlots slots,
                                          Environment environment) {
        return new ScheduledJobRunner(properties, launcher, slots, instanceName(environment));
    }

    /** {@code <spring.application.name>@<host>:<pid>}, which tells in batch_schedule_slot who ran a slot. */
    private static String instanceName(Environment environment) {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        return environment.getProperty("spring.application.name", "application") + "@" + host + ":"
                + ProcessHandle.current().pid();
    }
}
