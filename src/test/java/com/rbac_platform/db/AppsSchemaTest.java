package com.rbac_platform.db;

import static com.rbac_platform.db.Sql.count;
import static com.rbac_platform.db.Sql.exec;
import static com.rbac_platform.db.Sql.one;
import static com.rbac_platform.db.Sql.sqlState;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppsSchemaTest {

    private final MigratedDatabase db = MigratedDatabase.get();

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID insertApp(UUID tenantId, String clientId) {
        return db.inTx(null, null, c -> insertApp(c, tenantId, clientId));
    }

    private static UUID insertApp(Connection c, UUID tenantId, String clientId) {
        return one(c, UUID.class,
                "insert into apps.applications (tenant_id, client_id, name, type, token_endpoint_auth_method) "
                + "values (?, ?, 'App', 'web', 'client_secret_basic') returning id",
                tenantId, clientId);
    }

    private UUID insertApiKey(UUID tenantId, UUID appId, String prefix, String keyHash) {
        return db.inTx(null, null, c -> one(c, UUID.class,
                "insert into apps.api_keys (tenant_id, application_id, name, prefix, key_hash) "
                + "values (?, ?, 'key', ?, ?) returning id",
                tenantId, appId, prefix, keyHash));
    }

    private UUID insertSigningKey(UUID tenantId, String status) {
        return db.inTx(null, null, c -> one(c, UUID.class,
                "insert into apps.signing_keys (tenant_id, algorithm, public_jwk, private_key_encrypted, "
                + "encryption_key_id, status) values (?, 'ES256', '{}'::jsonb, '\\x00'::bytea, 'k1', ?) returning id",
                tenantId, status));
    }

    @Test
    void clientIdIsGloballyUnique() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        String clientId = "client-" + rand();
        insertApp(a, clientId);
        assertThat(sqlState(() -> insertApp(b, clientId))).isEqualTo("23505");
    }

    @Test
    void apiKeyPrefixIsGloballyUnique() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        UUID appA = insertApp(a, "client-" + rand());
        UUID appB = insertApp(b, "client-" + rand());
        String prefix = "rbk_" + rand();
        insertApiKey(a, appA, prefix, "hash-a");
        assertThat(sqlState(() -> insertApiKey(b, appB, prefix, "hash-b"))).isEqualTo("23505");
    }

    @Test
    void onlyOneActiveSigningKeyPerTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        insertSigningKey(a, "active");
        assertThat(sqlState(() -> insertSigningKey(a, "active"))).isEqualTo("23505");
        insertSigningKey(a, "next");
        insertSigningKey(a, "retired");
        insertSigningKey(a, "retired");
        insertSigningKey(b, "active");
    }

    @Test
    void signingKeyStatusAndAlgorithmAreChecked() {
        UUID a = db.insertTenant();
        assertThat(sqlState(() -> insertSigningKey(a, "bogus"))).isEqualTo("23514");
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into apps.signing_keys (tenant_id, algorithm, public_jwk, private_key_encrypted, "
                + "encryption_key_id, status) values (?, 'HS256', '{}'::jsonb, '\\x00'::bytea, 'k1', 'next')", a))))
                .isEqualTo("23514");
    }

    @Test
    void applicationTypeAndAuthMethodAreChecked() {
        UUID a = db.insertTenant();
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into apps.applications (tenant_id, client_id, name, type, token_endpoint_auth_method) "
                + "values (?, ?, 'App', 'desktop', 'none')", a, "client-" + rand())))).isEqualTo("23514");
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into apps.applications (tenant_id, client_id, name, type, token_endpoint_auth_method) "
                + "values (?, ?, 'App', 'web', 'basic')", a, "client-" + rand())))).isEqualTo("23514");
    }

    @Test
    void requirePkceDefaultsToTrue() {
        UUID a = db.insertTenant();
        UUID app = insertApp(a, "client-" + rand());
        db.inTx(null, null, c -> {
            assertThat(one(c, Boolean.class,
                    "select require_pkce from apps.applications where tenant_id = ? and id = ?", a, app)).isTrue();
            return null;
        });
    }

    @Test
    void updatedAtIsMaintained() {
        UUID a = db.insertTenant();
        UUID app = insertApp(a, "client-" + rand());
        db.inTx(null, null, c -> {
            exec(c, "update apps.applications set name = 'Renamed' where tenant_id = ? and id = ?", a, app);
            assertThat(count(c, "select count(*) from apps.applications "
                    + "where tenant_id = ? and id = ? and updated_at > created_at", a, app)).isEqualTo(1);
            return null;
        });
    }

    @Test
    void childRowsMustBelongToSameTenantAsApplication() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        UUID appA = insertApp(a, "client-" + rand());
        assertThat(sqlState(() -> insertApiKey(b, appA, "rbk_" + rand(), "h"))).isEqualTo("23503");
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into apps.client_secrets (tenant_id, application_id, secret_hash) values (?, ?, 'h')",
                b, appA)))).isEqualTo("23503");
    }

    @Test
    void deletingApplicationCascadesToSecretsAndKeys() {
        UUID a = db.insertTenant();
        UUID app = insertApp(a, "client-" + rand());
        insertApiKey(a, app, "rbk_" + rand(), "h");
        db.inTx(null, null, c -> exec(c,
                "insert into apps.client_secrets (tenant_id, application_id, secret_hash, hint) values (?, ?, 'h', 'abcd')",
                a, app));
        db.inTx(null, null, c -> exec(c, "delete from apps.applications where tenant_id = ? and id = ?", a, app));
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from apps.api_keys where tenant_id = ?", a)).isZero();
            assertThat(count(c, "select count(*) from apps.client_secrets where tenant_id = ?", a)).isZero();
            return null;
        });
    }

    @Test
    void resolveApiKeyWorksWithoutTenantContext() {
        UUID a = db.insertTenant();
        UUID app = insertApp(a, "client-" + rand());
        String prefix = "rbk_" + rand();
        UUID keyId = insertApiKey(a, app, prefix, "the-hash");
        db.inTx("rbac_app", null, c -> {
            assertThat(one(c, UUID.class, "select tenant_id from apps.resolve_api_key(?)", prefix)).isEqualTo(a);
            assertThat(one(c, UUID.class, "select api_key_id from apps.resolve_api_key(?)", prefix)).isEqualTo(keyId);
            assertThat(one(c, UUID.class, "select application_id from apps.resolve_api_key(?)", prefix))
                    .isEqualTo(app);
            assertThat(one(c, String.class, "select key_hash from apps.resolve_api_key(?)", prefix))
                    .isEqualTo("the-hash");
            assertThat(count(c, "select count(*) from apps.resolve_api_key(?)", "rbk_missing")).isZero();
            assertThat(count(c, "select count(*) from apps.api_keys")).isZero();
            return null;
        });
    }

    @Test
    void resolveApiKeyReturnsRevokedAndExpiredKeysForCallerToJudge() {
        UUID a = db.insertTenant();
        UUID app = insertApp(a, "client-" + rand());
        String prefix = "rbk_" + rand();
        insertApiKey(a, app, prefix, "h");
        db.inTx(null, null, c -> exec(c,
                "update apps.api_keys set revoked_at = now(), expires_at = now() - interval '1 day' "
                + "where prefix = ?", prefix));
        db.inTx("rbac_app", null, c -> {
            assertThat(count(c, "select count(*) from apps.resolve_api_key(?) "
                    + "where revoked_at is not null and expires_at is not null", prefix)).isEqualTo(1);
            return null;
        });
    }

    @Test
    void resolveApiKeyIsNotExecutableByPublic() {
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                    + "where n.nspname = 'apps' and p.proname = 'resolve_api_key' and p.prosecdef "
                    + "and has_function_privilege('rbac_app', p.oid, 'EXECUTE') "
                    + "and not has_function_privilege('anon', p.oid, 'EXECUTE') "
                    + "and not has_function_privilege('authenticated', p.oid, 'EXECUTE')")).isEqualTo(1);
            return null;
        });
    }

    @Test
    void sessionApplicationMustBelongToSameTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        UUID appA = insertApp(a, "client-" + rand());
        UUID userB = db.insertUser(b, "u-" + rand() + "@example.com");
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into identity.sessions (tenant_id, user_id, application_id, idle_expires_at, expires_at) "
                + "values (?, ?, ?, now() + interval '1 hour', now() + interval '1 day')",
                b, userB, appA)))).isEqualTo("23503");
    }

    @Test
    void sessionWithoutApplicationIsAllowedAndSameTenantApplicationAccepted() {
        UUID a = db.insertTenant();
        UUID appA = insertApp(a, "client-" + rand());
        UUID userA = db.insertUser(a, "u-" + rand() + "@example.com");
        db.inTx(null, null, c -> {
            exec(c, "insert into identity.sessions (tenant_id, user_id, idle_expires_at, expires_at) "
                    + "values (?, ?, now() + interval '1 hour', now() + interval '1 day')", a, userA);
            exec(c, "insert into identity.sessions (tenant_id, user_id, application_id, idle_expires_at, expires_at) "
                    + "values (?, ?, ?, now() + interval '1 hour', now() + interval '1 day')", a, userA, appA);
            return null;
        });
    }

    @Test
    void refreshTokenApplicationMustBelongToSameTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        UUID appA = insertApp(a, "client-" + rand());
        UUID userB = db.insertUser(b, "u-" + rand() + "@example.com");
        assertThat(sqlState(() -> db.inTx(null, null, c -> {
            UUID session = one(c, UUID.class,
                    "insert into identity.sessions (tenant_id, user_id, idle_expires_at, expires_at) "
                    + "values (?, ?, now() + interval '1 hour', now() + interval '1 day') returning id", b, userB);
            return exec(c, "insert into identity.refresh_tokens "
                    + "(tenant_id, session_id, application_id, token_hash, family_id, expires_at) "
                    + "values (?, ?, ?, ?, ?, now() + interval '1 day')",
                    b, session, appA, "th-" + rand(), UUID.randomUUID());
        }))).isEqualTo("23503");
    }

    @Test
    void deletingApplicationCascadesToSessionsAndRefreshTokens() {
        UUID a = db.insertTenant();
        UUID app = insertApp(a, "client-" + rand());
        UUID userA = db.insertUser(a, "u-" + rand() + "@example.com");
        db.inTx(null, null, c -> {
            UUID session = one(c, UUID.class,
                    "insert into identity.sessions (tenant_id, user_id, application_id, idle_expires_at, expires_at) "
                    + "values (?, ?, ?, now() + interval '1 hour', now() + interval '1 day') returning id",
                    a, userA, app);
            exec(c, "insert into identity.refresh_tokens "
                    + "(tenant_id, session_id, application_id, token_hash, family_id, expires_at) "
                    + "values (?, ?, ?, ?, ?, now() + interval '1 day')",
                    a, session, app, "th-" + rand(), UUID.randomUUID());
            return null;
        });
        db.inTx(null, null, c -> exec(c, "delete from apps.applications where tenant_id = ? and id = ?", a, app));
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from identity.sessions where tenant_id = ?", a)).isZero();
            assertThat(count(c, "select count(*) from identity.refresh_tokens where tenant_id = ?", a)).isZero();
            return null;
        });
    }
}
