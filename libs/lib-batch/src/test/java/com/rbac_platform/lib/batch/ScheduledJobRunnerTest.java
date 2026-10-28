package com.rbac_platform.lib.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import com.rbac_platform.testsupport.Sql;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The scheduler against the real job repository, one nested class per application context. The contexts that schedule
 * jobs are closed after their tests so no scheduler keeps firing into the tests that follow.
 */
class ScheduledJobRunnerTest {

    static final MigratedDatabase db = MigratedDatabase.get();

    static void registerDatabase(DynamicPropertyRegistry registry) {
        AppDatabase.register(registry, "identity_app");
        registry.add("rbac.batch.schema", () -> "identity");
    }

    /** noop, idle and spare do nothing; boom always fails. */
    @TestConfiguration
    static class JobsConfig {
        @Bean
        Job noop(JobRepository repository, PlatformTransactionManager tx) {
            return job("noop", (contribution, chunk) -> RepeatStatus.FINISHED, repository, tx);
        }

        @Bean
        Job idle(JobRepository repository, PlatformTransactionManager tx) {
            return job("idle", (contribution, chunk) -> RepeatStatus.FINISHED, repository, tx);
        }

        @Bean
        Job spare(JobRepository repository, PlatformTransactionManager tx) {
            return job("spare", (contribution, chunk) -> RepeatStatus.FINISHED, repository, tx);
        }

        @Bean
        Job boom(JobRepository repository, PlatformTransactionManager tx) {
            return job("boom", (contribution, chunk) -> {
                throw new IllegalStateException("boom");
            }, repository, tx);
        }

        private static Job job(String name, Tasklet tasklet, JobRepository repository, PlatformTransactionManager tx) {
            return new JobBuilder(name, repository)
                    .start(new StepBuilder(name + "-step", repository).tasklet(tasklet, tx).build())
                    .build();
        }
    }

    /** One execution of a job started by the scheduler, and whether its slot is in batch_schedule_slot. */
    record Run(Instant scheduledAt, String status, boolean slotClaimed) {
    }

    /** Scheduled executions of {@code jobName} whose slot is at or after {@code since}. */
    static List<Run> runs(String jobName, Instant since) {
        return db.inTx(null, null, c -> {
            List<Run> runs = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    select p.parameter_value::timestamptz as scheduled_at, e.status,
                           exists (select 1 from identity.batch_schedule_slot s
                                   where s.job_name = i.job_name
                                     and s.scheduled_at = p.parameter_value::timestamptz) as slot_claimed
                    from identity.batch_job_instance i
                    join identity.batch_job_execution e on e.job_instance_id = i.job_instance_id
                    join identity.batch_job_execution_params p on p.job_execution_id = e.job_execution_id
                    where i.job_name = ? and p.parameter_name = 'scheduledAt'
                      and p.parameter_value::timestamptz >= ?""")) {
                ps.setString(1, jobName);
                ps.setObject(2, since.atOffset(ZoneOffset.UTC));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        runs.add(new Run(rs.getObject(1, OffsetDateTime.class).toInstant(), rs.getString(2),
                                rs.getBoolean(3)));
                    }
                }
            }
            return runs;
        });
    }

    static long executions(String jobName) {
        return db.inTx(null, null, c -> Sql.count(c, "select count(*) from identity.batch_job_instance i "
                + "join identity.batch_job_execution e on e.job_instance_id = i.job_instance_id where i.job_name = ?",
                jobName));
    }

    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = BatchTestApplication.class, properties = {
            "rbac.batch.jobs.noop.cron=* * * * * *",
            "rbac.batch.jobs.idle.enabled=false",
            "rbac.batch.jobs.idle.cron=* * * * * *"})
    @Import(JobsConfig.class)
    @DirtiesContext
    class OnSchedule {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registerDatabase(registry);
        }

        @Test
        void configuredJobRunsOnSchedule() {
            Instant since = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(runs("noop", since)).extracting(Run::status).contains("COMPLETED"));
            assertThat(runs("noop", since)).allSatisfy(run -> assertThat(run.slotClaimed()).as("%s", run).isTrue());
        }

        @Test
        void disabledOrUnconfiguredJobsAreNotScheduled() {
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).until(() ->
                    executions("idle") == 0 && executions("spare") == 0);
        }
    }

    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = BatchTestApplication.class, properties = "rbac.batch.jobs.boom.cron=* * * * * *")
    @Import(JobsConfig.class)
    @DirtiesContext
    class FailingSchedule {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registerDatabase(registry);
        }

        @Test
        void aFailingRunDoesNotStopLaterRuns() {
            Instant since = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(runs("boom", since).stream().filter(run -> run.status().equals("FAILED"))
                            .map(Run::scheduledAt).distinct()).hasSizeGreaterThanOrEqualTo(2));
        }
    }

    /** Nothing scheduled: the tests fire the runner themselves. */
    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = BatchTestApplication.class)
    @Import(JobsConfig.class)
    class ManualFire {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registerDatabase(registry);
        }

        @Autowired
        ScheduledJobRunner runner;

        @Test
        void secondInstanceSkipsAClaimedSlot() {
            Instant slot = Instant.parse("2001-01-01T00:00:00Z");
            db.inTx(null, null, c -> Sql.exec(c, "insert into identity.batch_schedule_slot "
                    + "(job_name, scheduled_at, claimed_by) values ('noop', ?, 'other-instance')",
                    slot.atOffset(ZoneOffset.UTC)));

            runner.fire("noop", slot);

            assertThat(runs("noop", slot)).noneMatch(run -> run.scheduledAt().equals(slot));
        }

        @Test
        void failedRunIsNotRepeatedForTheSameSlot() {
            Instant slot = Instant.parse("2001-01-01T00:01:00Z");

            runner.fire("boom", slot);
            runner.fire("boom", slot);

            assertThat(runs("boom", slot)).filteredOn(run -> run.scheduledAt().equals(slot))
                    .extracting(Run::status).containsExactly("FAILED");
        }
    }

    @Test
    void misconfiguredJobsFailStartup() {
        Job noop = mock(Job.class);
        when(noop.getName()).thenReturn("noop");
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BatchAutoConfiguration.class))
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(JobOperator.class, () -> mock(JobOperator.class))
                .withBean(JobRepository.class, () -> mock(JobRepository.class))
                .withBean("noop", Job.class, () -> noop)
                .withPropertyValues("rbac.batch.schema=identity");

        runner.withPropertyValues("rbac.batch.jobs.noop.cron=0 0 * * * *")
                .run(context -> assertThat(context).hasNotFailed());
        for (String[] misconfiguration : new String[][]{
                {"rbac.batch.jobs.ghost.cron=0 0 * * * *", "rbac.batch.jobs.ghost"},
                {"rbac.batch.jobs.noop.enabled=true", "rbac.batch.jobs.noop.cron"},
                {"rbac.batch.jobs.noop.cron=not a cron", "rbac.batch.jobs.noop.cron"}}) {
            runner.withPropertyValues(misconfiguration[0]).run(context -> {
                assertThat(context).as(misconfiguration[0]).hasFailed();
                assertThat(context.getStartupFailure()).as(misconfiguration[0])
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(misconfiguration[1]);
            });
        }
    }
}
