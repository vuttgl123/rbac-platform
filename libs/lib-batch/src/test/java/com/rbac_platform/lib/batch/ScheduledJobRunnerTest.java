package com.rbac_platform.lib.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
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
                .withPropertyValues("rbac.batch.schema=identity", "spring.batch.job.enabled=false",
                        "spring.batch.jdbc.table-prefix=identity.batch_");

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

    /** A runner for one job, {@code slow}, firing every second; every slot is free. */
    static ScheduledJobRunner everySecond(JobLaunchService launcher) {
        when(launcher.jobNames()).thenReturn(Set.of("slow"));
        ScheduleSlots slots = mock(ScheduleSlots.class);
        when(slots.claim(any(), any(), any())).thenReturn(true);
        BatchProperties properties = new BatchProperties("identity", 2,
                Map.of("slow", new BatchProperties.JobSchedule(true, "* * * * * *")));
        return new ScheduledJobRunner(properties, launcher, slots, "test@host:1");
    }

    @Test
    void schedulingStartsWithTheLifecycleNotWhenTheBeansExist() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        JobLaunchService launcher = mock(JobLaunchService.class);
        when(launcher.launch(eq("slow"), anyMap())).thenAnswer(invocation -> {
            launches.incrementAndGet();
            return mock(JobExecution.class);
        });
        ScheduledJobRunner runner = everySecond(launcher);

        runner.afterSingletonsInstantiated();
        Thread.sleep(1500);
        assertThat(launches).as("nothing fires before start").hasValue(0);
        assertThat(runner.isRunning()).isFalse();
        assertThat(runner.isAutoStartup()).isTrue();

        runner.start();
        try {
            assertThat(runner.isRunning()).isTrue();
            await().atMost(Duration.ofSeconds(3)).until(() -> launches.get() >= 1);
        } finally {
            runner.stop();
        }
        assertThat(runner.isRunning()).isFalse();
    }

    @Test
    void stopWithACallbackReturnsAtOnceAndCallsBackWhenTheRunningJobFinishes() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        JobLaunchService launcher = mock(JobLaunchService.class);
        when(launcher.launch(eq("slow"), anyMap())).thenAnswer(invocation -> {
            started.countDown();
            finished.set(release.await(10, TimeUnit.SECONDS));
            return mock(JobExecution.class);
        });
        ScheduledJobRunner runner = everySecond(launcher);
        runner.afterSingletonsInstantiated();
        runner.start();
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();

        CountDownLatch stopped = new CountDownLatch(1);
        long calling = System.nanoTime();
        runner.stop(stopped::countDown);
        // The lifecycle processor stops every bean of the phase this way, then waits for all callbacks at once.
        assertThat(Duration.ofNanos(System.nanoTime() - calling)).isLessThan(Duration.ofMillis(500));
        assertThat(stopped.await(300, TimeUnit.MILLISECONDS)).as("callback waits for the job").isFalse();

        release.countDown();
        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(finished).isTrue();
    }

    @Test
    void shutdownLetsARunningJobFinishAndDropsLaterFires() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger launches = new AtomicInteger();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        JobLaunchService launcher = mock(JobLaunchService.class);
        when(launcher.jobNames()).thenReturn(Set.of("slow"));
        when(launcher.launch(eq("slow"), anyMap())).thenAnswer(invocation -> {
            launches.incrementAndGet();
            started.countDown();
            try {
                Thread.sleep(1000);
                finished.set(true);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            return mock(JobExecution.class);
        });
        ScheduleSlots slots = mock(ScheduleSlots.class);
        when(slots.claim(any(), any(), any())).thenReturn(true);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BatchAutoConfiguration.class))
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(JobOperator.class, () -> mock(JobOperator.class))
                .withBean(JobRepository.class, () -> mock(JobRepository.class))
                .withBean(JobLaunchService.class, () -> launcher)
                .withBean(ScheduleSlots.class, () -> slots)
                .withPropertyValues("rbac.batch.schema=identity", "spring.batch.job.enabled=false",
                        "spring.batch.jdbc.table-prefix=identity.batch_", "rbac.batch.jobs.slow.cron=* * * * * *")
                .run(context -> {
                    assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                    long closing = System.nanoTime();
                    context.close();
                    assertThat(Duration.ofNanos(System.nanoTime() - closing)).isLessThan(Duration.ofSeconds(5));
                });

        // The fire that was running finished undisturbed; the one the cron would have scheduled next never ran.
        assertThat(finished).isTrue();
        assertThat(interrupted).isFalse();
        assertThat(launches).hasValue(1);
    }
}
