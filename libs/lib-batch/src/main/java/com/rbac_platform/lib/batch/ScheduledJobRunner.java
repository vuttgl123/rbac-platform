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
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.SmartLifecycle;
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
 * enabled job without a cron, or a cron that does not parse fails startup naming the property. Scheduling begins
 * when the context starts its lifecycle beans and ends when it stops them, in {@link #PHASE}.
 */
public class ScheduledJobRunner implements SmartInitializingSingleton, SmartLifecycle {

    static final String SCHEDULED_AT = "scheduledAt";

    /**
     * The outbox relay's phase (the SmartLifecycle default): both drain their work in flight in the same shutdown
     * phase, side by side, so a service that runs both waits for the longer of the two, not their sum. It is also
     * above the web server's graceful shutdown ({@code DEFAULT_PHASE - 1024}): jobs start after the server is up
     * and stop before it drains its requests.
     */
    static final int PHASE = SmartLifecycle.DEFAULT_PHASE;

    /** How long a stop waits for running jobs; below Boot's default 30 s spring.lifecycle.timeout-per-shutdown-phase. */
    static final int AWAIT_TERMINATION_SECONDS = 25;

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobRunner.class);

    private final Map<String, BatchProperties.JobSchedule> schedules;
    private final int poolSize;
    private final JobLaunchService launcher;
    private final ScheduleSlots slots;
    private final String claimedBy;

    private final Object lifecycle = new Object();
    /** The scheduler of the current run, from start to stop; null while stopped. Guarded by {@link #lifecycle}. */
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

    /** Fails startup on a bad schedule, before any lifecycle bean starts. */
    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (scheduler != null) {
                return;
            }
            ThreadPoolTaskScheduler next = new ThreadPoolTaskScheduler();
            next.setPoolSize(poolSize);
            next.setThreadNamePrefix("batch-scheduler-");
            // On stop a running job finishes (for up to AWAIT_TERMINATION_SECONDS) instead of being interrupted and
            // left STARTED; fires the cron has scheduled but not started are dropped rather than waited for. The wait
            // must fit in spring.lifecycle.timeout-per-shutdown-phase, past which the context closes regardless.
            next.setWaitForTasksToCompleteOnShutdown(true);
            next.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
            next.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            next.initialize();
            schedules.forEach((name, schedule) -> {
                if (schedule.enabled()) {
                    schedule(next, name, schedule.cron());
                }
            });
            scheduler = next;
        }
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

    private void schedule(ThreadPoolTaskScheduler scheduler, String jobName, String cron) {
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

    /** Stops firing and waits for the jobs running now, up to {@link #AWAIT_TERMINATION_SECONDS}. */
    @Override
    public void stop() {
        ThreadPoolTaskScheduler current = detach();
        if (current != null) {
            current.shutdown();
        }
    }

    /**
     * Stops firing and returns at once; {@code callback} runs once the jobs running now have finished, or after
     * {@link #AWAIT_TERMINATION_SECONDS}. The lifecycle processor stops every bean of a phase this way and then waits
     * for all their callbacks together.
     */
    @Override
    public void stop(Runnable callback) {
        ThreadPoolTaskScheduler current = detach();
        if (current == null) {
            callback.run();
            return;
        }
        Thread.ofPlatform().name("batch-scheduler-stop").daemon(true).start(() -> {
            try {
                current.shutdown();
            } finally {
                callback.run();
            }
        });
    }

    private ThreadPoolTaskScheduler detach() {
        synchronized (lifecycle) {
            ThreadPoolTaskScheduler current = scheduler;
            scheduler = null;
            return current;
        }
    }

    /** True from start until stop is called; the jobs running then may still be finishing. */
    @Override
    public boolean isRunning() {
        synchronized (lifecycle) {
            return scheduler != null;
        }
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
