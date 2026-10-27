package com.rbac_platform.db;

import static com.rbac_platform.testsupport.Sql.count;
import static com.rbac_platform.testsupport.Sql.exec;
import static com.rbac_platform.testsupport.Sql.one;
import static com.rbac_platform.testsupport.Sql.sqlState;
import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.testsupport.MigratedDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenancySchemaTest {

    private final MigratedDatabase db = MigratedDatabase.get();

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void slugMustBeLowercaseKebab() {
        assertThat(sqlState(() -> db.inTx(null, null, c ->
                exec(c, "insert into tenancy.tenants (slug, name) values ('Bad Slug', 'x')"))))
                .isEqualTo("23514");
    }

    @Test
    void slugIsUnique() {
        String slug = "dup-" + rand();
        db.inTx(null, null, c -> exec(c, "insert into tenancy.tenants (slug, name) values (?, 'x')", slug));
        assertThat(sqlState(() -> db.inTx(null, null, c ->
                exec(c, "insert into tenancy.tenants (slug, name) values (?, 'y')", slug))))
                .isEqualTo("23505");
    }

    @Test
    void statusMustBeKnownAndDefaultsToActive() {
        UUID id = db.insertTenant();
        db.inTx(null, null, c -> {
            assertThat(one(c, String.class, "select status from tenancy.tenants where id = ?", id))
                    .isEqualTo("active");
            return null;
        });
        assertThat(sqlState(() -> db.inTx(null, null, c ->
                exec(c, "update tenancy.tenants set status = 'bogus' where id = ?", id))))
                .isEqualTo("23514");
    }

    @Test
    void updatedAtIsMaintainedByTrigger() {
        UUID id = db.insertTenant();
        db.inTx(null, null, c -> {
            exec(c, "update tenancy.tenants set updated_at = now() - interval '1 day' where id = ?", id);
            exec(c, "update tenancy.tenants set name = 'renamed' where id = ?", id);
            assertThat(count(c, "select count(*) from tenancy.tenants "
                    + "where id = ? and updated_at > now() - interval '1 minute'", id)).isEqualTo(1);
            return null;
        });
    }

    @Test
    void appRoleReadsAllTenantsUpdatesOnlyOwn() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        db.inTx("rbac_app", a, c -> {
            assertThat(exec(c, "update tenancy.tenants set name='x' where id=?", b)).isZero();
            assertThat(exec(c, "update tenancy.tenants set name='x' where id=?", a)).isEqualTo(1);
            assertThat(count(c, "select count(*) from tenancy.tenants where id in (?,?)", a, b)).isEqualTo(2);
            return null;
        });
    }

    @Test
    void appRoleCanUpdateOnlyNameAndSettingsOfOwnTenant() {
        UUID a = db.insertTenant();
        String[] protectedColumns = {
            "status = 'suspended'", "plan = 'enterprise'", "slug = 'hijack-" + rand() + "'",
            "authz_model_revision = 9000000000000000000", "deleted_at = now()", "mfa_min = 'required'",
        };
        for (String set : protectedColumns) {
            assertThat(sqlState(() -> db.inTx("rbac_app", a, c ->
                    exec(c, "update tenancy.tenants set " + set + " where id = ?", a))))
                    .as(set).isEqualTo("42501");
        }
        db.inTx("rbac_app", a, c -> {
            assertThat(exec(c, "update tenancy.tenants set name = 'Renamed', settings = '{\"k\":1}'::jsonb "
                    + "where id = ?", a)).isEqualTo(1);
            return null;
        });
        db.inTx(null, null, c -> {
            assertThat(one(c, String.class, "select concat_ws(' ', name, settings ->> 'k', status, plan, "
                    + "slug like 'hijack-%', authz_model_revision >= 9000000000000000000, deleted_at is null) "
                    + "from tenancy.tenants where id = ?", a))
                    .isEqualTo("Renamed 1 active standard f f t");
            return null;
        });
    }

    @Test
    void settingsMfaCannotDropBelowOperatorFloor() {
        UUID a = db.insertTenant();
        db.inTx("rbac_admin", null, c -> exec(c, "update tenancy.tenants "
                + "set mfa_min = 'optional', settings = '{\"mfa\":{\"required\":\"optional\"}}' where id = ?", a));
        for (String mfa : new String[] {"{}", "{\"mfa\":{\"required\":\"off\"}}"}) {
            assertThat(sqlState(() -> db.inTx("rbac_app", a, c -> exec(c,
                    "update tenancy.tenants set settings = ?::jsonb where id = ?", mfa, a))))
                    .as(mfa).isEqualTo("23514");
        }
        for (String level : new String[] {"optional", "required"}) {
            db.inTx("rbac_app", a, c -> exec(c,
                    "update tenancy.tenants set settings = jsonb_build_object('mfa', jsonb_build_object('required', ?::text)) "
                    + "where id = ?", level, a));
        }
        // The floor cannot be raised over the tenant's current setting either, and must be a known level.
        db.inTx("rbac_app", a, c -> exec(c,
                "update tenancy.tenants set settings = '{\"mfa\":{\"required\":\"optional\"}}' where id = ?", a));
        assertThat(sqlState(() -> db.inTx("rbac_admin", null, c ->
                exec(c, "update tenancy.tenants set mfa_min = 'required' where id = ?", a)))).isEqualTo("23514");
        assertThat(sqlState(() -> db.inTx("rbac_admin", null, c ->
                exec(c, "update tenancy.tenants set mfa_min = 'sometimes' where id = ?", a)))).isEqualTo("23514");
    }

    @Test
    void settingsMfaMustBeAKnownLevel() {
        UUID a = db.insertTenant();
        assertThat(sqlState(() -> db.inTx("rbac_app", a, c -> exec(c,
                "update tenancy.tenants set settings = '{\"mfa\":{\"required\":\"yes\"}}' where id = ?", a))))
                .isEqualTo("23514");
        db.inTx(null, null, c -> {
            assertThat(one(c, String.class, "select mfa_min from tenancy.tenants where id = ?", a)).isEqualTo("off");
            return null;
        });
    }

    @Test
    void tenantsTableHasRlsEnabledAndForced() {
        db.inTx(null, null, c -> {
            assertThat(one(c, Boolean.class, "select relrowsecurity and relforcerowsecurity from pg_class "
                    + "where oid = 'tenancy.tenants'::regclass")).isTrue();
            return null;
        });
    }

    @Test
    void appRoleCannotInsertTenant() {
        assertThat(sqlState(() -> db.inTx("rbac_app", null, c ->
                exec(c, "insert into tenancy.tenants (slug, name) values ('app-" + rand() + "', 'x')"))))
                .isEqualTo("42501");
    }

    @Test
    void appRoleCannotDeleteTenant() {
        UUID a = db.insertTenant();
        assertThat(sqlState(() -> db.inTx("rbac_app", a, c ->
                exec(c, "delete from tenancy.tenants where id = ?", a))))
                .isEqualTo("42501");
    }

    @Test
    void adminRoleManagesAllTenants() {
        UUID a = db.insertTenant();
        db.inTx("rbac_admin", null, c -> {
            assertThat(exec(c, "update tenancy.tenants set name='adm', status = 'suspended' where id=?", a))
                    .isEqualTo(1);
            return null;
        });
    }

    @Test
    void adminRoleCreatesTenantsOnlyThroughCreateTenant() {
        assertThat(sqlState(() -> db.inTx("rbac_admin", null, c ->
                exec(c, "insert into tenancy.tenants (slug, name) values ('adm-" + rand() + "', 'x')"))))
                .isEqualTo("42501");
    }

    @Test
    void resolveDomainOnlyReturnsVerifiedDomains() {
        UUID a = db.insertTenant();
        String verified = "verified-" + rand() + ".com";
        String pending = "pending-" + rand() + ".com";
        db.inTx(null, null, c -> {
            exec(c, "insert into tenancy.tenant_domains (tenant_id, domain, verified_at) values (?, ?, now())",
                    a, verified);
            exec(c, "insert into tenancy.tenant_domains (tenant_id, domain) values (?, ?)", a, pending);
            return null;
        });
        db.inTx("rbac_app", null, c -> {
            assertThat(one(c, UUID.class, "select tenancy.resolve_domain(?)", verified)).isEqualTo(a);
            assertThat(one(c, UUID.class, "select tenancy.resolve_domain(?)", pending)).isNull();
            assertThat(one(c, UUID.class, "select tenancy.resolve_domain(?)", verified.toUpperCase())).isEqualTo(a);
            return null;
        });
    }

    @Test
    void resolveDomainIsNotExecutableByPublic() {
        db.inTx(null, null, c -> {
            assertThat(one(c, Boolean.class,
                    "select has_function_privilege('rbac_app', 'tenancy.resolve_domain(text)', 'EXECUTE')")).isTrue();
            assertThat(one(c, Boolean.class,
                    "select has_function_privilege('anon', 'tenancy.resolve_domain(text)', 'EXECUTE')")).isFalse();
            return null;
        });
    }

    @Test
    void domainsAreGloballyUnique() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        String domain = "shared-" + rand() + ".com";
        db.inTx(null, null, c -> exec(c, "insert into tenancy.tenant_domains (tenant_id, domain) values (?, ?)",
                a, domain));
        assertThat(sqlState(() -> db.inTx(null, null, c ->
                exec(c, "insert into tenancy.tenant_domains (tenant_id, domain) values (?, upper(?))", b, domain))))
                .isEqualTo("23505");
    }
}
