package com.rbac_platform.lib.batch;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

/**
 * Runs the jobs configured under rbac.batch.jobs on their cron (UTC). Every instance of a service fires every
 * schedule; a fire first claims its slot in {@link ScheduleSlots}, so only one instance runs each scheduled time.
 * The job is launched with the identifying parameter {@code scheduledAt}, the time the cron scheduled the fire, so
 * each scheduled time is its own job instance.
 *
 * <p>Configuration is checked once every bean exists: a schedule for a job no {@code Job} bean is named after, an
 * enabled job without a cron, or a cron that does not parse fails startup naming the property.
 */
public class ScheduledJobRunner implements SmartInitializingSingleton, DisposableBean {

    static final String SCHEDULED_AT = "scheduledAt";

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobRunner.class);

    private final Map<String, BatchProperties.JobSchedule> schedules;
    private final int poolSize;
    private final JobLaunchService launcher;
    private final ScheduleSlots slots;
    private final String claimedBy;
    private ThreadPoolTaskScheduler scheduler;

    /** @param claimedBy who this instance is in the slots it claims: {@code <application>@<host>:<pid>} */
    public ScheduledJobRunner(BatchProperties properties, JobLaunchService launcher, ScheduleSlots slots,
                              String claimedBy) {
        this.schedules = properties.jobs();
        this.poolSize = properties.schedulerPoolSize();
        this.launcher = launcher;
        this.slots = slots;
        this.claimedBy = claimedBy;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("batch-scheduler-");
        scheduler.initialize();
        schedules.forEach((name, schedule) -> {
            if (schedule.enabled()) {
                schedule(name, schedule.cron());
            }
        });
    }

    private void validate() {
        Set<String> jobNames = launcher.jobNames();
        schedules.forEach((name, schedule) -> {
            String property = "rbac.batch.jobs." + name;
            if (!jobNames.contains(name)) {
                throw new IllegalStateException(property + " configures a job, but no Job bean is named '" + name
                        + "' (Job beans: " + jobNames + ")");
            }
            String cron = schedule.cron();
            if (schedule.enabled() && (cron == null || cron.isBlank())) {
                throw new IllegalStateException(property + ".cron is required while the job is enabled");
            }
            if (cron != null && !cron.isBlank() && !CronExpression.isValidExpression(cron)) {
                throw new IllegalStateException(property + ".cron '" + cron + "' is not a valid cron expression");
            }
        });
    }

    private void schedule(String jobName, String cron) {
        CronTrigger cronTrigger = new CronTrigger(cron, ZoneOffset.UTC);
        // The scheduler asks for the next time only after a fire returns, so while a fire runs this holds the time
        // the cron scheduled it for: the same on every instance, whatever each one's timing jitter.
        AtomicReference<Instant> scheduledAt = new AtomicReference<>();
        Trigger trigger = context -> {
            Instant next = cronTrigger.nextExecution(context);
            scheduledAt.set(next);
            return next;
        };
        scheduler.schedule(() -> fire(jobName, scheduledAt.get()), trigger);
        log.info("Scheduled job {} on cron '{}' (UTC)", jobName, cron);
    }

    /** One fire of a schedule; never throws, so a failure leaves the later fires scheduled. */
    void fire(String jobName, Instant scheduledAt) {
        try {
            if (!slots.claim(jobName, scheduledAt, claimedBy)) {
                log.debug("Job {} for {} already claimed by another instance; skipping", jobName, scheduledAt);
                return;
            }
            JobExecution execution = launcher.launch(jobName, Map.of(SCHEDULED_AT, scheduledAt.toString()));
            if (execution.getStatus() == BatchStatus.FAILED) {
                log.error("Scheduled job {} for {} failed: execution {}", jobName, scheduledAt, execution.getId());
            }
        } catch (Exception e) {
            log.error("Scheduled job {} for {} could not run", jobName, scheduledAt, e);
        }
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
