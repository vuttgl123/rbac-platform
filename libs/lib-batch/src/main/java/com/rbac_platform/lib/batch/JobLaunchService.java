package com.rbac_platform.lib.batch;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.dao.EmptyResultDataAccessException;

/**
 * Starts, restarts and recovers the service's jobs by name through the Batch {@link JobOperator}. Jobs run on the
 * caller's thread, so each method returns the finished execution. Batch's checked exceptions (instance already
 * complete, execution still running, ...) come out as {@link IllegalStateException} with the Batch one as the cause.
 */
public class JobLaunchService {

    private final JobOperator operator;
    private final JobRepository repository;
    private final Map<String, Job> jobs;

    public JobLaunchService(JobOperator operator, JobRepository repository, Collection<Job> jobs) {
        this.operator = operator;
        this.repository = repository;
        this.jobs = jobs.stream().collect(Collectors.toUnmodifiableMap(Job::getName, Function.identity(), (a, b) -> {
            throw new IllegalStateException("Two Job beans are named '" + a.getName() + "'");
        }));
    }

    /**
     * Runs a new instance of the job, or the failed instance with the same parameters again.
     *
     * @param identifyingParameters the job instance's identity: the same values name the same instance
     */
    public JobExecution launch(String jobName, Map<String, String> identifyingParameters) {
        Job job = jobs.get(jobName);
        if (job == null) {
            throw new IllegalArgumentException("No Job bean named '" + jobName + "'");
        }
        JobParametersBuilder parameters = new JobParametersBuilder();
        identifyingParameters.forEach(parameters::addString);
        try {
            return operator.start(job, parameters.toJobParameters());
        } catch (JobExecutionException e) {
            throw new IllegalStateException("Job '" + jobName + "' could not start: " + e.getMessage(), e);
        }
    }

    /** Runs the instance of a FAILED or STOPPED execution again, from where it stopped. */
    public JobExecution restart(long executionId) {
        try {
            return operator.restart(execution(executionId));
        } catch (JobExecutionException e) {
            throw new IllegalStateException("Execution " + executionId + " could not restart: " + e.getMessage(), e);
        }
    }

    /**
     * Marks an execution FAILED that the repository still shows as running although no process runs it (its instance
     * died mid-run), so that {@link #restart} accepts it. Only call it for an execution known to be abandoned.
     */
    public JobExecution recover(long executionId) {
        return operator.recover(execution(executionId));
    }

    Set<String> jobNames() {
        return jobs.keySet();
    }

    private JobExecution execution(long executionId) {
        JobExecution execution;
        try {
            execution = repository.getJobExecution(executionId);
        } catch (EmptyResultDataAccessException e) {
            // The JDBC repository throws for an unknown id where the interface allows null.
            execution = null;
        }
        if (execution == null) {
            throw new IllegalArgumentException("No job execution " + executionId);
        }
        return execution;
    }
}
