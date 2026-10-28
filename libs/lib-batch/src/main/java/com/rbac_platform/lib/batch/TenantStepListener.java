package com.rbac_platform.lib.batch;

import com.rbac_platform.lib.db.TenantScope;
import org.slf4j.MDC;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * Puts a partition's tenant in the log context (MDC key {@value TenantScope#MDC_KEY}, as {@code TenantScope} does)
 * while its step runs. A step without a tenant in its execution context is left alone.
 */
public class TenantStepListener implements StepExecutionListener {

    @Override
    public void beforeStep(StepExecution stepExecution) {
        ExecutionContext context = stepExecution.getExecutionContext();
        if (context.containsKey(TenantPartitioner.TENANT_ID_KEY)) {
            MDC.put(TenantScope.MDC_KEY, context.getString(TenantPartitioner.TENANT_ID_KEY));
        }
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (stepExecution.getExecutionContext().containsKey(TenantPartitioner.TENANT_ID_KEY)) {
            MDC.remove(TenantScope.MDC_KEY);
        }
        return null;
    }
}
