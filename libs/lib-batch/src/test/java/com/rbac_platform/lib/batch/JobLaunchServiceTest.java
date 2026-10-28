package com.rbac_platform.lib.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import com.rbac_platform.testsupport.Sql;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(classes = BatchTestApplication.class)
@Import(JobLaunchServiceTest.FlakyJobConfig.class)
class JobLaunchServiceTest {

    /** flaky fails the first time it runs for a given run parameter and succeeds every time after. */
    @TestConfiguration
    static class FlakyJobConfig {
        @Bean
        Job flaky(JobRepository repository, PlatformTransactionManager tx) {
            Set<Object> failedOnce = ConcurrentHashMap.newKeySet();
            return new JobBuilder("flaky", repository)
                    .start(new StepBuilder("flaky-step", repository).tasklet((contribution, chunk) -> {
                        if (failedOnce.add(chunk.getStepContext().getJobParameters().get("run"))) {
                            throw new IllegalStateException("first run fails");
                        }
                        return RepeatStatus.FINISHED;
                    }, tx).build())
                    .build();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppDatabase.register(registry, "identity_app");
        registry.add("rbac.batch.schema", () -> "identity");
    }

    private final MigratedDatabase db = MigratedDatabase.get();

    @Autowired
    JobLaunchService launcher;

    @Test
    void launchRestartAndRecover() {
        JobExecution failed = launcher.launch("flaky", Map.of("run", UUID.randomUUID().toString()));
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(launcher.restart(failed.getId()).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // A second instance whose process "died" mid-run: the repository still says STARTED, with no end time.
        JobExecution crashed = launcher.launch("flaky", Map.of("run", UUID.randomUUID().toString()));
        assertThat(crashed.getStatus()).isEqualTo(BatchStatus.FAILED);
        db.inTx(null, null, c -> Sql.exec(c, "update identity.batch_job_execution set status = 'STARTED', "
                + "end_time = null where job_execution_id = ?", crashed.getId()));

        assertThatThrownBy(() -> launcher.restart(crashed.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(JobRestartException.class);

        assertThat(launcher.recover(crashed.getId()).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(launcher.restart(crashed.getId()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void unknownJobOrExecutionIsRefused() {
        assertThatThrownBy(() -> launcher.launch("no-such-job", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-job");
        assertThatThrownBy(() -> launcher.restart(Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Long.toString(Long.MAX_VALUE));
    }
}
