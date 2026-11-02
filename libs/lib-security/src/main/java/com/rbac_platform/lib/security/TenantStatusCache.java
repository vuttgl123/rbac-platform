package com.rbac_platform.lib.security;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Whether a tenant is {@code active} and owns a given issuer slug, read from {@code tenancy.tenants} and cached
 * for {@code ttl}. The slug check binds a token's {@code tid} to the issuer that signed it, so one tenant's
 * signing key cannot mint tokens for another tenant. Tokens are
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

    // status and slug are null when the tenant does not exist.
    private record Entry(String status, String slug, long expiresAtNanos) {
    }

    public TenantStatusCache(JdbcClient jdbc, Duration ttl) {
        this.jdbc = jdbc;
        this.ttlNanos = ttl.toNanos();
    }

    /** True only if the tenant exists, its status is {@code active} and its slug is {@code slug}. */
    public boolean isActive(UUID tenantId, String slug) {
        Entry entry = lookup(tenantId);
        return "active".equals(entry.status()) && slug != null && slug.equals(entry.slug());
    }

    private Entry lookup(UUID tenantId) {
        long now = System.nanoTime();
        Entry entry = entries.get(tenantId);
        if (entry != null && now - entry.expiresAtNanos() < 0) {
            return entry;
        }
        Entry fresh = jdbc.sql("select status, slug from tenancy.tenants where id = ?")
                .param(tenantId)
                .query((rs, row) -> new Entry(rs.getString("status"), rs.getString("slug"), now + ttlNanos))
                .optional()
                .orElse(new Entry(null, null, now + ttlNanos));
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        entries.put(tenantId, fresh);
        return fresh;
    }
}
