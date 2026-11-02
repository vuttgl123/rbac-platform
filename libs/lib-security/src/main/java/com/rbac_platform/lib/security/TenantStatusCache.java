package com.rbac_platform.lib.security;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Whether a tenant is {@code active}, read from {@code tenancy.tenants} and cached for {@code ttl}. Tokens are
 * self-contained, so without this check a suspended tenant's users would keep access until their tokens expire;
 * with it they lose access within {@code ttl}, at the cost of one indexed lookup per tenant per {@code ttl}.
 * Every service role may read every row of {@code tenancy.tenants}, so no tenant scope is needed.
 */
public class TenantStatusCache {

    // Only tenants named in validly signed tokens get here, so the map stays small; the cap is a backstop.
    private static final int MAX_ENTRIES = 10_000;

    private final JdbcClient jdbc;
    private final long ttlNanos;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    private record Entry(boolean active, long expiresAtNanos) {
    }

    public TenantStatusCache(JdbcClient jdbc, Duration ttl) {
        this.jdbc = jdbc;
        this.ttlNanos = ttl.toNanos();
    }

    /** True only if the tenant exists and its status is {@code active}. */
    public boolean isActive(UUID tenantId) {
        long now = System.nanoTime();
        Entry entry = entries.get(tenantId);
        if (entry != null && now - entry.expiresAtNanos() < 0) {
            return entry.active();
        }
        boolean active = jdbc.sql("select status from tenancy.tenants where id = ?")
                .param(tenantId)
                .query(String.class)
                .optional()
                .map("active"::equals)
                .orElse(false);
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        entries.put(tenantId, new Entry(active, now + ttlNanos));
        return active;
    }
}
