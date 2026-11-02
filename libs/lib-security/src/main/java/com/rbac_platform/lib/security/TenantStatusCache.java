package com.rbac_platform.lib.security;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Which active tenant owns an issuer slug, read from {@code tenancy.tenants} and cached for {@code ttl}.
 *
 * <ul>
 *   <li>Checked before any call to identity, so a token naming an unknown or inactive slug costs one indexed
 *       local query and is rejected without network traffic.</li>
 *   <li>Checked again against the token's {@code tid}, which binds the tenant to the issuer that signed the
 *       token: one tenant's signing key cannot mint tokens for another tenant.</li>
 *   <li>Tokens are self-contained, so this is also how a suspended tenant's users lose access within
 *       {@code ttl} instead of when their tokens expire.</li>
 * </ul>
 *
 * Unknown slugs are cached too (as absent), and the cache evicts least recently used entries, so a flood of
 * made-up slugs costs bounded memory and cannot push out the tenants in use. Every service role may read every
 * row of {@code tenancy.tenants}, so no tenant scope is needed.
 */
public class TenantStatusCache {

    static final int MAX_ENTRIES = 10_000;

    private final JdbcClient jdbc;
    private final long ttlNanos;
    private final Map<String, Entry> entries = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /** {@code activeTenantId} is null when no active tenant has the slug. */
    private record Entry(UUID activeTenantId, long expiresAtNanos) {
    }

    private record Row(UUID id, String status) {
    }

    public TenantStatusCache(JdbcClient jdbc, Duration ttl) {
        this.jdbc = jdbc;
        this.ttlNanos = ttl.toNanos();
    }

    /** The id of the active tenant with this slug, or null when there is none (unknown, suspended, deleted). */
    public UUID activeTenant(String slug) {
        long now = System.nanoTime();
        synchronized (entries) {
            Entry entry = entries.get(slug);
            if (entry != null && now - entry.expiresAtNanos() < 0) {
                return entry.activeTenantId();
            }
        }
        UUID active = jdbc.sql("select id, status from tenancy.tenants where slug = ?")
                .param(slug)
                .query((rs, row) -> new Row(rs.getObject("id", UUID.class), rs.getString("status")))
                .optional()
                .filter(tenant -> "active".equals(tenant.status()))
                .map(Row::id)
                .orElse(null);
        synchronized (entries) {
            entries.put(slug, new Entry(active, now + ttlNanos));
        }
        return active;
    }

    /** True only if {@code tenantId} is the active tenant that owns {@code slug}. */
    public boolean isActive(UUID tenantId, String slug) {
        return tenantId != null && tenantId.equals(activeTenant(slug));
    }
}
