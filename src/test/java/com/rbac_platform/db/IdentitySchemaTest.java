package com.rbac_platform.db;

import static com.rbac_platform.db.Sql.count;
import static com.rbac_platform.db.Sql.exec;
import static com.rbac_platform.db.Sql.one;
import static com.rbac_platform.db.Sql.sqlState;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentitySchemaTest {

    private final MigratedDatabase db = MigratedDatabase.get();

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID insertSession(UUID tenantId, UUID userId) {
        return db.inTx(null, null, c -> insertSession(c, tenantId, userId));
    }

    private static UUID insertSession(Connection c, UUID tenantId, UUID userId) {
        return one(c, UUID.class,
                "insert into identity.sessions (tenant_id, user_id, idle_expires_at, expires_at) "
                + "values (?, ?, now() + interval '1 hour', now() + interval '1 day') returning id",
                tenantId, userId);
    }

    private static UUID insertRefreshToken(Connection c, UUID tenantId, UUID sessionId, String tokenHash) {
        return one(c, UUID.class,
                "insert into identity.refresh_tokens (tenant_id, session_id, token_hash, family_id, expires_at) "
                + "values (?, ?, ?, ?, now() + interval '1 day') returning id",
                tenantId, sessionId, tokenHash, UUID.randomUUID());
    }

    @Test
    void emailUniquePerTenantIgnoringCase() {
        UUID a = db.insertTenant();
        db.insertUser(a, "an@example.com");
        assertThat(sqlState(() -> db.insertUser(a, "An@Example.com"))).isEqualTo("23505");
    }

    @Test
    void sameEmailAllowedInOtherTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        db.insertUser(a, "x@example.com");
        db.insertUser(b, "x@example.com"); // must not throw
    }

    @Test
    void softDeletedEmailCanBeReused() {
        UUID a = db.insertTenant();
        UUID u = db.insertUser(a, "reuse@example.com");
        db.inTx(null, null, c -> exec(c,
                "update identity.users set deleted_at = now() where tenant_id = ? and id = ?", a, u));
        db.insertUser(a, "reuse@example.com"); // must not throw
    }

    @Test
    void usernameAndPhoneAreUniquePerTenantUntilSoftDeleted() {
        UUID a = db.insertTenant();
        db.inTx(null, null, c -> exec(c,
                "insert into identity.users (tenant_id, username, phone) values (?, 'Bob', '+84900000001')", a));
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into identity.users (tenant_id, username) values (?, 'bob')", a))))
                .isEqualTo("23505");
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into identity.users (tenant_id, phone) values (?, '+84900000001')", a))))
                .isEqualTo("23505");
    }

    @Test
    void externalIdIsUniquePerTenantButMayRepeatAsNull() {
        UUID a = db.insertTenant();
        db.inTx(null, null, c -> {
            exec(c, "insert into identity.users (tenant_id, email) values (?, ?)", a, "n1-" + rand() + "@e.com");
            exec(c, "insert into identity.users (tenant_id, email) values (?, ?)", a, "n2-" + rand() + "@e.com");
            exec(c, "insert into identity.users (tenant_id, email, external_id) values (?, ?, 'emp-1')",
                    a, "e1-" + rand() + "@e.com");
            return null;
        });
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into identity.users (tenant_id, email, external_id) values (?, ?, 'emp-1')",
                a, "e2-" + rand() + "@e.com"))))
                .isEqualTo("23505");
    }

    @Test
    void userNeedsEmailUsernameOrPhone() {
        UUID a = db.insertTenant();
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into identity.users (tenant_id, display_name) values (?, 'nobody')", a))))
                .isEqualTo("23514");
    }

    @Test
    void userStatusMustBeKnown() {
        UUID a = db.insertTenant();
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into identity.users (tenant_id, email, status) values (?, 'z@e.com', 'bogus')", a))))
                .isEqualTo("23514");
    }

    @Test
    void sessionCannotReferenceUserOfAnotherTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        UUID userOfB = db.insertUser(b, "b@example.com");
        assertThat(sqlState(() -> insertSession(a, userOfB))).isEqualTo("23503");
    }

    @Test
    void deletingUserCascadesCredentialsSessionsAndTokens() {
        UUID a = db.insertTenant();
        UUID u = db.insertUser(a, "cascade@example.com");
        db.inTx(null, null, c -> {
            exec(c, "insert into identity.password_credentials (tenant_id, user_id, password_hash) values (?, ?, 'h')", a, u);
            exec(c, "insert into identity.password_history (tenant_id, user_id, password_hash) values (?, ?, 'h')", a, u);
            exec(c, "insert into identity.mfa_factors (tenant_id, user_id, type, secret_encrypted, encryption_key_id) "
                    + "values (?, ?, 'totp', '\\x00'::bytea, 'k1')", a, u);
            exec(c, "insert into identity.mfa_recovery_codes (tenant_id, user_id, code_hash) values (?, ?, 'ch')", a, u);
            UUID provider = one(c, UUID.class,
                    "insert into identity.identity_providers (tenant_id, type, name) values (?, 'oidc', 'idp') returning id", a);
            exec(c, "insert into identity.federated_identities (tenant_id, user_id, provider_id, provider_subject) "
                    + "values (?, ?, ?, 'sub-1')", a, u, provider);
            exec(c, "insert into identity.one_time_tokens (tenant_id, user_id, purpose, token_hash, expires_at) "
                    + "values (?, ?, 'password_reset', ?, now() + interval '1 hour')", a, u, "ott-" + rand());
            UUID s = insertSession(c, a, u);
            insertRefreshToken(c, a, s, "rt-" + rand());
            return null;
        });
        db.inTx(null, null, c -> exec(c, "delete from identity.users where tenant_id = ? and id = ?", a, u));
        db.inTx(null, null, c -> {
            for (String t : new String[] {"password_credentials", "password_history", "mfa_factors",
                    "mfa_recovery_codes", "federated_identities", "one_time_tokens", "sessions"}) {
                assertThat(count(c, "select count(*) from identity." + t + " where user_id = ?", u))
                        .as(t).isZero();
            }
            assertThat(count(c, "select count(*) from identity.refresh_tokens where tenant_id = ?", a))
                    .as("refresh_tokens").isZero();
            return null;
        });
    }

    @Test
    void refreshTokenHashIsUnique() {
        UUID a = db.insertTenant();
        UUID u = db.insertUser(a, "rt@example.com");
        UUID s = insertSession(a, u);
        db.inTx(null, null, c -> insertRefreshToken(c, a, s, "same-hash"));
        assertThat(sqlState(() -> db.inTx(null, null, c -> insertRefreshToken(c, a, s, "same-hash"))))
                .isEqualTo("23505");
    }

    @Test
    void oneTimeTokenHashIsUniqueAndPurposeChecked() {
        UUID a = db.insertTenant();
        String sql = "insert into identity.one_time_tokens (tenant_id, purpose, token_hash, expires_at) "
                + "values (?, ?, ?, now() + interval '1 hour')";
        db.inTx(null, null, c -> exec(c, sql, a, "invitation", "ott-same"));
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c, sql, a, "magic_link", "ott-same"))))
                .isEqualTo("23505");
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c, sql, a, "bogus", "ott-other"))))
                .isEqualTo("23514");
    }

    @Test
    void refreshTokenCannotReferenceSessionOfAnotherTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        UUID sessionOfB = insertSession(b, db.insertUser(b, "rb@example.com"));
        assertThat(sqlState(() -> db.inTx(null, null, c -> insertRefreshToken(c, a, sessionOfB, "h-" + rand()))))
                .isEqualTo("23503");
    }

    @Test
    void groupKeyIsUniquePerTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        String sql = "insert into identity.groups (tenant_id, key, name) values (?, 'eng', 'Engineering')";
        db.inTx(null, null, c -> exec(c, sql, a));
        db.inTx(null, null, c -> exec(c, sql, b)); // other tenant is fine
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c, sql, a)))).isEqualTo("23505");
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into identity.groups (tenant_id, key, name, source) values (?, 'x', 'X', 'bogus')", a))))
                .isEqualTo("23514");
    }

    @Test
    void federatedIdentityIsUniquePerProviderSubject() {
        UUID a = db.insertTenant();
        UUID u1 = db.insertUser(a, "f1@example.com");
        UUID u2 = db.insertUser(a, "f2@example.com");
        UUID provider = db.inTx(null, null, c -> one(c, UUID.class,
                "insert into identity.identity_providers (tenant_id, type, name) values (?, 'saml', 'okta') returning id", a));
        String sql = "insert into identity.federated_identities (tenant_id, user_id, provider_id, provider_subject) "
                + "values (?, ?, ?, 'sub-x')";
        db.inTx(null, null, c -> exec(c, sql, a, u1, provider));
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c, sql, a, u2, provider)))).isEqualTo("23505");
    }

    @Test
    void secretsAreStoredAsHashesAndEncryptedBytes() {
        db.inTx(null, null, c -> {
            assertThat(one(c, String.class, "select data_type from information_schema.columns "
                    + "where table_schema = 'identity' and table_name = 'password_credentials' "
                    + "and column_name = 'password_hash'")).isEqualTo("text");
            assertThat(one(c, String.class, "select data_type from information_schema.columns "
                    + "where table_schema = 'identity' and table_name = 'mfa_factors' "
                    + "and column_name = 'secret_encrypted'")).isEqualTo("bytea");
            assertThat(one(c, String.class, "select data_type from information_schema.columns "
                    + "where table_schema = 'identity' and table_name = 'identity_providers' "
                    + "and column_name = 'client_secret_encrypted'")).isEqualTo("bytea");
            return null;
        });
    }

    @Test
    void updatedAtIsMaintained() {
        UUID a = db.insertTenant();
        UUID u = db.insertUser(a, "upd@example.com");
        db.inTx(null, null, c -> {
            exec(c, "update identity.users set display_name = 'New Name' where tenant_id = ? and id = ?", a, u);
            assertThat(count(c, "select count(*) from identity.users "
                    + "where tenant_id = ? and id = ? and updated_at > created_at", a, u)).isEqualTo(1);
            return null;
        });
    }

    @Test
    void appRoleSeesOnlyOwnTenantUsers() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        db.insertUser(a, "own@example.com");
        db.insertUser(b, "other@example.com");
        db.inTx("rbac_app", a, c -> {
            assertThat(count(c, "select count(*) from identity.users where tenant_id = ?", b)).isZero();
            assertThat(count(c, "select count(*) from identity.users where tenant_id = ?", a)).isGreaterThanOrEqualTo(1);
            return null;
        });
    }

    @Test
    void findUserByEmailIgnoresCaseAndStaysInTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        String local = "find-" + rand();
        UUID mine = db.insertUser(a, local + ".Mixed@Example.com");
        UUID gone = db.insertUser(a, "gone-" + local + "@example.com");
        db.inTx(null, null, c -> exec(c,
                "update identity.users set deleted_at = now() where tenant_id = ? and id = ?", a, gone));
        db.insertUser(b, "theirs-" + local + "@example.com");
        String find = "select id from identity.find_user_by_email(?)";
        for (String role : new String[] {"rbac_app", "rbac_admin"}) {
            db.inTx(role, a, c -> {
                assertThat(one(c, UUID.class, find, local.toUpperCase() + ".mixed@EXAMPLE.COM")).as(role).isEqualTo(mine);
                assertThat(one(c, UUID.class, find, "theirs-" + local + "@example.com")).as(role).isNull();
                assertThat(one(c, UUID.class, find, "gone-" + local + "@example.com")).as(role).isNull();
                return null;
            });
        }
        // No tenant context: nothing, even for an exact match.
        assertThat(db.<UUID>inTx("rbac_app", null, c -> one(c, UUID.class, find, local + ".Mixed@Example.com")))
                .isNull();
    }

    @Test
    void findUserByUsernameIgnoresCaseAndStaysInTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        String name = "Login-" + rand();
        UUID mine = db.inTx(null, null, c -> one(c, UUID.class,
                "insert into identity.users (tenant_id, username) values (?, ?) returning id", a, name));
        db.inTx(null, null, c -> exec(c,
                "insert into identity.users (tenant_id, username) values (?, ?)", b, "other-" + name));
        String find = "select id from identity.find_user_by_username(?)";
        db.inTx("rbac_app", a, c -> {
            assertThat(one(c, UUID.class, find, name.toLowerCase())).isEqualTo(mine);
            assertThat(one(c, UUID.class, find, "other-" + name)).isNull();
            return null;
        });
        assertThat(db.<UUID>inTx("rbac_app", null, c -> one(c, UUID.class, find, name))).isNull();
    }

    @Test
    void userLookupFunctionsAreGrantedOnlyToPlatformRoles() {
        db.inTx(null, null, c -> {
            for (String fn : new String[] {"identity.find_user_by_email(text)", "identity.find_user_by_username(text)"}) {
                assertThat(one(c, Boolean.class, "select has_function_privilege('rbac_app', ?, 'EXECUTE') "
                        + "and has_function_privilege('rbac_admin', ?, 'EXECUTE') "
                        + "and not has_function_privilege('anon', ?, 'EXECUTE')", fn, fn, fn)).as(fn).isTrue();
            }
            return null;
        });
    }

    @Test
    void loginRolesResolveExtensionsOnTheirSearchPath() {
        db.inTx(null, null, c -> {
            for (String role : new String[] {"rbac_app", "rbac_admin"}) {
                assertThat(one(c, String.class, "select array_to_string(s.setconfig, ',') from pg_db_role_setting s "
                        + "where s.setrole = ?::regrole and s.setdatabase = 0", role))
                        .as(role).isEqualTo("search_path=\"$user\", extensions");
            }
            return null;
        });
    }

    @Test
    void sessionsHaveFullUserIndexForCascades() {
        db.inTx(null, null, c -> {
            assertThat(one(c, String.class, "select indexdef from pg_indexes "
                    + "where schemaname = 'identity' and indexname = 'sessions_user_idx'"))
                    .isEqualTo("CREATE INDEX sessions_user_idx ON identity.sessions USING btree (tenant_id, user_id)");
            return null;
        });
    }
}
