package com.rbac_platform.db;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in one transaction bound to a tenant. Row-level security reads the tenant from the
 * transaction-local setting app.tenant_id, so every query inside sees only that tenant's rows and
 * the setting ends with the transaction instead of staying on the pooled connection.
 */
@Component
public class TenantScope {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public TenantScope(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public <T> T call(UUID tenantId, Supplier<T> work) {
        Objects.requireNonNull(tenantId, "tenantId");
        // Joining an outer transaction would leave this tenant set for the rest of it.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TenantScope.call must start the transaction, but the caller is already in a transaction");
        }
        return transactions.execute(status -> {
            jdbc.sql("select set_config('app.tenant_id', ?, true)").param(tenantId.toString()).query().singleValue();
            return work.get();
        });
    }
}
