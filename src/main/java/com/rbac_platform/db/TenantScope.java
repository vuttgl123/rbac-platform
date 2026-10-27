package com.rbac_platform.db;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in one transaction bound to a tenant. Row-level security reads the tenant from the
 * transaction-local setting app.tenant_id, so every query inside sees only that tenant's rows and
 * the setting ends with the transaction instead of staying on the pooled connection.
 * While the work runs, the tenant is also in the log context (MDC key tenantId).
 */
@Component
public class TenantScope {

    public static final String MDC_KEY = "tenantId";

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
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, tenantId.toString());
        try {
            return transactions.execute(status -> {
                jdbc.sql("select set_config('app.tenant_id', ?, true)").param(tenantId.toString()).query().singleValue();
                return work.get();
            });
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
