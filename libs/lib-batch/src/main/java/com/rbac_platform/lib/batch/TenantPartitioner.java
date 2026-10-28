package com.rbac_platform.lib.batch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Fans a step out over the active tenants: one partition {@code tenant-<uuid>} per tenant, ordered by id, whose
 * execution context holds the tenant under {@link #TENANT_ID_KEY}. A worker step that uses a
 * {@link TenantScopedTransactionManager} then runs every transaction bound to its partition's tenant. The grid size
 * is ignored: the tenants decide how many partitions there are.
 */
public class TenantPartitioner implements Partitioner {

    public static final String TENANT_ID_KEY = "tenantId";

    private final JdbcClient jdbc;

    public TenantPartitioner(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        List<UUID> tenants = jdbc.sql("select id from tenancy.tenants where status = 'active' order by id")
                .query(UUID.class).list();
        Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
        for (UUID tenant : tenants) {
            ExecutionContext context = new ExecutionContext();
            context.putString(TENANT_ID_KEY, tenant.toString());
            partitions.put("tenant-" + tenant, context);
        }
        return partitions;
    }
}
