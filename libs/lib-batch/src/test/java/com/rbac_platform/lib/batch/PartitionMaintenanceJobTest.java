package com.rbac_platform.lib.batch;

import static com.rbac_platform.testsupport.Sql.exec;
import static com.rbac_platform.testsupport.Sql.one;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/** The audit service's partition maintenance, run as audit_app against the audit job repository. */
@SpringBootTest(classes = BatchTestApplication.class)
@Import(PartitionMaintenanceJobTest.MaintenanceJobConfig.class)
class PartitionMaintenanceJobTest {

    private static final String PARTITION_OF_MONTH_PLUS_3 = "select 'audit.events_p' || "
            + "to_char(date_trunc('month', now() at time zone 'utc') + interval '3 months', 'YYYYMM')";

    @TestConfiguration
    static class MaintenanceJobConfig {
        @Bean
        Job partitionMaintenance(JobRepository repository, PlatformTransactionManager tx, JdbcClient jdbc) {
            return PartitionMaintenanceJob.create(repository, tx, jdbc, "audit.maintain_partitions");
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppDatabase.register(registry, "audit_app");
        registry.add("rbac.batch.schema", () -> "audit");
    }

    private final MigratedDatabase db = MigratedDatabase.get();

    @Autowired
    JobLaunchService launcher;

    @Test
    void recreatesAMissingFuturePartition() {
        // The embedded database is shared: empty and drop the (future) month +3 partition, and let the job recreate it.
        String partition = db.<String>inTx(null, null, c -> one(c, String.class, PARTITION_OF_MONTH_PLUS_3));
        try {
            db.inTx(null, null, c -> {
                if (one(c, String.class, "select to_regclass(?)::text", partition) != null) {
                    exec(c, "delete from " + partition);
                    exec(c, "drop table " + partition);
                }
                return null;
            });

            JobExecution execution = launcher.launch(PartitionMaintenanceJob.NAME,
                    Map.of("run", UUID.randomUUID().toString()));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getStepExecutions()).singleElement().satisfies(step -> {
                assertThat(step.getStepName()).isEqualTo("partition-maintenance-step");
                assertThat(step.getExecutionContext().getInt("created")).isEqualTo(1);
            });
            assertThat(db.<String>inTx(null, null, c -> one(c, String.class, "select to_regclass(?)::text", partition)))
                    .isEqualTo(partition);
        } finally {
            // Whatever happened above, leave every future partition in place for the tests that follow.
            db.inTx(null, null, c -> one(c, Integer.class, "select audit.maintain_partitions()"));
        }
    }

    @Test
    void rejectsAnUnsafeFunctionName() {
        assertThatThrownBy(() -> PartitionMaintenanceJob.create(mock(JobRepository.class),
                mock(PlatformTransactionManager.class), mock(JdbcClient.class), "audit.maintain_partitions(); drop"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
