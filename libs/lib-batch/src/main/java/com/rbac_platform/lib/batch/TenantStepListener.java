package com.rbac_platform.lib.batch;

import com.rbac_platform.lib.db.TenantScope;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * Puts a partition's tenant in the log context (MDC key {@value TenantScope#MDC_KEY}, as {@code TenantScope} does)
 * while its step runs, and restores what was there before afterwards: partitions run on the launching thread, which
 * may itself be inside a {@code TenantScope}. A step without a tenant in its execution context is left alone.
 */
public class TenantStepListener implements StepExecutionListener {

    /** The MDC tenant each running step replaced, by step execution id (one listener serves every partition). */
    private final Map<Long, Optional<String>> replaced = new ConcurrentHashMap<>();

    @Override
    public void beforeStep(StepExecution stepExecution) {
        ExecutionContext context = stepExecution.getExecutionContext();
        if (context.containsKey(TenantPartitioner.TENANT_ID_KEY)) {
            replaced.put(stepExecution.getId(), Optional.ofNullable(MDC.get(TenantScope.MDC_KEY)));
            MDC.put(TenantScope.MDC_KEY, context.getString(TenantPartitioner.TENANT_ID_KEY));
        }
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        Optional<String> previous = replaced.remove(stepExecution.getId());
        if (previous != null) {
            previous.ifPresentOrElse(tenant -> MDC.put(TenantScope.MDC_KEY, tenant),
                    () -> MDC.remove(TenantScope.MDC_KEY));
        }
        return null;
    }
}
