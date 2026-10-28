package com.rbac_platform.lib.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.lib.db.TenantScope;
import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Tenant fan-out: every partition's transactions are bound to its tenant, so row-level security shows each one only
 * that tenant's users. The embedded database is shared, so there may be partitions for other tests' tenants too.
 */
@SpringBootTest(classes = BatchTestApplication.class)
@Import(TenantPartitionTest.FanOutJobsConfig.class)
class TenantPartitionTest {

    /** What one transaction of a worker saw: the tenant of its step, the users it could read and the log context. */
    record Sighting(String stepTenant, String emails, String mdcTenant) {
    }

    static final List<Sighting> sightings = new CopyOnWriteArrayList<>();

    static Sighting look(JdbcClient jdbc) {
        String stepTenant = StepSynchronizationManager.getContext().getStepExecution().getExecutionContext()
                .getString(TenantPartitioner.TENANT_ID_KEY);
        String emails = jdbc.sql("select string_agg(email::text, ',' order by email) from identity.users")
                .query(String.class).optional().orElse(null);
        return new Sighting(stepTenant, emails, MDC.get(TenantScope.MDC_KEY));
    }

    /** A {@link ListItemReader} of three items, started over for each partition (the partitions share the step). */
    static final class ThreeItemsPerStep implements ItemStreamReader<String> {
        private ListItemReader<String> items;

        @Override
        public void open(ExecutionContext executionContext) {
            items = new ListItemReader<>(List.of("a", "b", "c"));
        }

        @Override
        public String read() {
            return items.read();
        }
    }

    @TestConfiguration
    static class FanOutJobsConfig {
        @Bean
        Job taskletFanOut(JobRepository repository, DataSource dataSource, JdbcClient jdbc) {
            Step worker = new StepBuilder("tasklet-worker", repository)
                    .tasklet((contribution, chunk) -> {
                        sightings.add(look(jdbc));
                        return RepeatStatus.FINISHED;
                    }, new TenantScopedTransactionManager(dataSource))
                    .listener(new TenantStepListener())
                    .build();
            return fanOut("tasklet-fan-out", worker, repository, jdbc);
        }

        @Bean
        Job chunkFanOut(JobRepository repository, DataSource dataSource, JdbcClient jdbc) {
            Step worker = new StepBuilder("chunk-worker", repository)
                    .<String, String>chunk(2)
                    .transactionManager(new TenantScopedTransactionManager(dataSource))
                    .reader(new ThreeItemsPerStep())
                    .writer(items -> sightings.add(look(jdbc)))
                    .listener(new TenantStepListener())
                    .build();
            return fanOut("chunk-fan-out", worker, repository, jdbc);
        }

        @Bean
        Job withoutTenant(JobRepository repository, DataSource dataSource) {
            return new JobBuilder("without-tenant", repository)
                    .start(new StepBuilder("untenanted-step", repository)
                            .tasklet((contribution, chunk) -> RepeatStatus.FINISHED,
                                    new TenantScopedTransactionManager(dataSource))
                            .build())
                    .build();
        }

        private static Job fanOut(String name, Step worker, JobRepository repository, JdbcClient jdbc) {
            return new JobBuilder(name, repository)
                    .start(new StepBuilder(name + "-manager", repository)
                            .partitioner(worker.getName(), new TenantPartitioner(jdbc))
                            .step(worker)
                            .build())
                    .build();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppDatabase.register(registry, "identity_app");
        registry.add("rbac.batch.schema", () -> "identity");
    }

    static UUID tenantA;
    static UUID tenantB;
    static String emailA;
    static String emailB;

    @BeforeAll
    static void twoTenantsWithOneUserEach() {
        MigratedDatabase db = MigratedDatabase.get();
        tenantA = db.insertTenant();
        tenantB = db.insertTenant();
        emailA = "a-" + tenantA + "@example.test";
        emailB = "b-" + tenantB + "@example.test";
        db.insertUser(tenantA, emailA);
        db.insertUser(tenantB, emailB);
    }

    @BeforeEach
    void clearSightings() {
        sightings.clear();
    }

    @Autowired
    JobLaunchService launcher;

    @Test
    void taskletPartitionsSeeOnlyTheirTenant() {
        JobExecution execution = launcher.launch("tasklet-fan-out", Map.of("run", UUID.randomUUID().toString()));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions()).extracting(StepExecution::getStepName)
                .contains("tasklet-worker:tenant-" + tenantA, "tasklet-worker:tenant-" + tenantB);
        assertThat(sightings).contains(
                new Sighting(tenantA.toString(), emailA, tenantA.toString()),
                new Sighting(tenantB.toString(), emailB, tenantB.toString()));
        assertThat(sightings).allSatisfy(sighting ->
                assertThat(sighting.mdcTenant()).isEqualTo(sighting.stepTenant()));
        assertThat(MDC.get(TenantScope.MDC_KEY)).isNull();
    }

    @Test
    void chunkTransactionsSeeOnlyTheirTenant() {
        JobExecution execution = launcher.launch("chunk-fan-out", Map.of("run", UUID.randomUUID().toString()));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // Three items in chunks of two: two transactions per partition.
        assertThat(sightings).filteredOn(sighting -> sighting.stepTenant().equals(tenantA.toString()))
                .containsExactly(new Sighting(tenantA.toString(), emailA, tenantA.toString()),
                        new Sighting(tenantA.toString(), emailA, tenantA.toString()));
        assertThat(sightings).filteredOn(sighting -> sighting.stepTenant().equals(tenantB.toString()))
                .containsExactly(new Sighting(tenantB.toString(), emailB, tenantB.toString()),
                        new Sighting(tenantB.toString(), emailB, tenantB.toString()));
        assertThat(sightings).allSatisfy(sighting ->
                assertThat(sighting.mdcTenant()).isEqualTo(sighting.stepTenant()));
    }

    @Test
    void stepWithoutATenantFails() {
        JobExecution execution = launcher.launch("without-tenant", Map.of("run", UUID.randomUUID().toString()));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions()).singleElement().satisfies(step -> {
            assertThat(step.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(step.getExitStatus().getExitDescription()).contains("TenantPartitioner");
        });
    }
}
