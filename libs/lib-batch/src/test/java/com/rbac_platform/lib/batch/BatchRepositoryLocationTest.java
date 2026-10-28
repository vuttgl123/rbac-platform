package com.rbac_platform.lib.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import com.rbac_platform.testsupport.Sql;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@SpringBootTest(classes = BatchTestApplication.class)
@Import(BatchRepositoryLocationTest.NoopJobConfig.class)
class BatchRepositoryLocationTest {

    @TestConfiguration
    static class NoopJobConfig {
        @Bean
        Job noop(JobRepository repository, PlatformTransactionManager tx) {
            return new JobBuilder("noop", repository)
                    .start(new StepBuilder("noop-step", repository)
                            .tasklet((contribution, chunk) -> RepeatStatus.FINISHED, tx).build())
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

    @Autowired
    JobRepository repository;

    @Test
    void jobMetadataIsWrittenIntoTheServiceSchema() {
        JobExecution execution = launcher.launch("noop", Map.of("run", UUID.randomUUID().toString()));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        db.inTx(null, null, c -> {
            assertThat(Sql.count(c, "select count(*) from identity.batch_job_instance where job_name = 'noop'"))
                    .isPositive();
            assertThat(Sql.count(c, "select count(*) from identity.batch_job_execution where job_execution_id = ?",
                    execution.getId())).isEqualTo(1);
            assertThat(Sql.count(c, "select count(*) from audit.batch_job_instance where job_name = 'noop'"))
                    .isZero();
            return null;
        });
    }

    @Test
    void unsafeSchemaFailsStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BatchAutoConfiguration.class))
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(JobOperator.class, () -> mock(JobOperator.class))
                .withBean(JobRepository.class, () -> mock(JobRepository.class))
                .withPropertyValues("rbac.batch.schema=identity; drop table x")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasStackTraceContaining("rbac.batch.schema must match");
                });
    }

    @Test
    void jobRepositoryCreatesAtTheConnectionsDefaultIsolation() throws Exception {
        // Batch 6 creates instances and executions at SERIALIZABLE, where two concurrent launches can fail each other
        // with 40001. The schedule slot and JOB_INST_UN already keep out duplicates, so Postgres's read committed is
        // enough. It must be DEFAULT rather than READ_COMMITTED: any explicit level makes Spring ask the driver for the
        // current one (a SHOW round trip in pgjdbc) and may set it on the session, which under Supavisor's transaction
        // pooling would leak to whichever client gets that backend next.
        TransactionInterceptor transactions = Arrays.stream(((Advised) repository).getAdvisors())
                .map(Advisor::getAdvice)
                .filter(TransactionInterceptor.class::isInstance)
                .map(TransactionInterceptor.class::cast)
                .findFirst().orElseThrow();
        for (Method create : List.of(
                JobRepository.class.getMethod("createJobInstance", String.class, JobParameters.class),
                JobRepository.class.getMethod("createJobExecution", JobInstance.class, JobParameters.class,
                        ExecutionContext.class))) {
            assertThat(transactions.getTransactionAttributeSource().getTransactionAttribute(create, JobRepository.class))
                    .as(create.getName())
                    .extracting(TransactionDefinition::getIsolationLevel)
                    .isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        }
    }

    @Test
    void startupFailsWithoutTheBatchDefaults() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BatchAutoConfiguration.class))
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(JobOperator.class, () -> mock(JobOperator.class))
                .withBean(JobRepository.class, () -> mock(JobRepository.class))
                .withPropertyValues("rbac.batch.schema=identity");

        for (String[] properties : new String[][]{
                {},
                {"spring.batch.job.enabled=false"},
                {"spring.batch.jdbc.table-prefix=identity.batch_"},
                {"spring.batch.job.enabled=false", "spring.batch.jdbc.table-prefix=audit.batch_"}}) {
            runner.withPropertyValues(properties).run(context -> {
                assertThat(context).as(Arrays.toString(properties)).hasFailed();
                assertThat(context.getStartupFailure()).as(Arrays.toString(properties))
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("classpath:rbac/batch-defaults.yaml");
            });
        }
        runner.withPropertyValues("spring.batch.job.enabled=false",
                        "spring.batch.jdbc.table-prefix=${rbac.batch.schema}.batch_")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
