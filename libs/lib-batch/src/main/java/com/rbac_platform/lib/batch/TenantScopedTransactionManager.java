package com.rbac_platform.lib.batch;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * The transaction manager of a tenant-partitioned worker step: every transaction it begins is bound to the tenant
 * in the running step's execution context (put there by {@link TenantPartitioner}), the way {@code TenantScope}
 * binds one, so row-level security shows the step only that tenant's rows and the setting ends with the transaction.
 *
 * <p>A job builds one per step, {@code new TenantScopedTransactionManager(dataSource)}. It must never be a bean:
 * a {@code PlatformTransactionManager} bean would replace Boot's for the whole service.
 */
public class TenantScopedTransactionManager extends DataSourceTransactionManager {

    private static final String SET_TENANT = "select set_config('app.tenant_id', ?, true)";

    public TenantScopedTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        // Checked before a connection is taken, so a step without a tenant fails with this exception itself rather
        // than with one wrapped as "could not open JDBC connection".
        tenantId();
        super.doBegin(transaction, definition);
    }

    @Override
    protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(connection, definition);
        // The connection is not bound to the thread yet, so this runs on it directly, as the superclass does.
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
            statement.setString(1, tenantId());
            statement.execute();
        }
    }

    private static String tenantId() {
        StepContext step = StepSynchronizationManager.getContext();
        ExecutionContext context = step == null ? null : step.getStepExecution().getExecutionContext();
        if (context == null || !context.containsKey(TenantPartitioner.TENANT_ID_KEY)) {
            throw new IllegalStateException("No " + TenantPartitioner.TENANT_ID_KEY + " in the execution context of "
                    + (step == null ? "the current step (none is running)" : "step " + step.getStepName())
                    + ": a TenantScopedTransactionManager belongs to the worker step of a TenantPartitioner");
        }
        return context.getString(TenantPartitioner.TENANT_ID_KEY);
    }
}
