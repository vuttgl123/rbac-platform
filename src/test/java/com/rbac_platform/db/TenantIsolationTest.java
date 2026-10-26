package com.rbac_platform.db;

import static com.rbac_platform.db.Sql.count;
import static com.rbac_platform.db.Sql.exec;
import static com.rbac_platform.db.Sql.one;
import static com.rbac_platform.db.Sql.sqlState;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Generic, metadata-driven tenant isolation checks; later tasks' tables are covered automatically. */
class TenantIsolationTest {

    private static final String SCHEMAS = "array['tenancy','identity','apps','authz','audit']";

    private final MigratedDatabase db = MigratedDatabase.get();

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void insertDomain(UUID tenantId) {
        db.inTx(null, null, c -> exec(c,
                "insert into tenancy.tenant_domains (tenant_id, domain) values (?, ?)",
                tenantId, "iso-" + rand() + ".com"));
    }

    @Test
    void everyTenantScopedTableForcesRlsWithStandardPolicy() {
        db.inTx(null, null, c -> {
            List<String> tables = new ArrayList<>();
            try (Statement s = c.createStatement();
                 var rs = s.executeQuery(
                         "select n.nspname || '.' || cl.relname from pg_class cl "
                         + "join pg_namespace n on n.oid = cl.relnamespace "
                         + "where n.nspname = any(" + SCHEMAS + ") and cl.relkind in ('r','p') "
                         + "and not cl.relispartition "
                         + "and exists (select 1 from pg_attribute a where a.attrelid = cl.oid "
                         + "  and a.attname = 'tenant_id' and a.attnum > 0 and not a.attisdropped) "
                         + "order by 1")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            assertThat(tables).as("tenant-scoped tables found (guard against a vacuous pass)").isNotEmpty();

            for (String t : tables) {
                assertThat(one(c, Boolean.class,
                        "select relrowsecurity and relforcerowsecurity from pg_class where oid = ?::regclass", t))
                        .as("%s has RLS enabled and forced", t)
                        .isTrue();
                String[] parts = t.split("\\.");
                assertThat(count(c,
                        "select count(*) from pg_policies where schemaname = ? and tablename = ? "
                        + "and 'rbac_app' = any(roles) "
                        + "and qual like '%current_tenant_id()%' and with_check like '%current_tenant_id()%'",
                        parts[0], parts[1]))
                        .as("%s has a standard rbac_app policy with USING and WITH CHECK", t)
                        .isGreaterThanOrEqualTo(1);
            }
            return null;
        });
    }

    @Test
    void partitionChildrenAreNotGrantedToAppRole() {
        db.inTx(null, null, c -> {
            assertThat(count(c,
                    "select count(*) from pg_class cl join pg_namespace n on n.oid = cl.relnamespace "
                    + "where cl.relispartition and n.nspname = any(" + SCHEMAS + ") "
                    + "and has_table_privilege('rbac_app', cl.oid, 'SELECT')"))
                    .isZero();
            return null;
        });
    }

    @Test
    void noContextMeansNoRows() {
        UUID a = db.insertTenant();
        insertDomain(a);
        assertThat(db.<Long>inTx("rbac_app", null, c -> count(c, "select count(*) from tenancy.tenant_domains")))
                .isZero();
    }

    @Test
    void contextSeesOnlyOwnRows() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        insertDomain(a);
        insertDomain(b);
        db.inTx("rbac_app", a, c -> {
            assertThat(count(c, "select count(*) from tenancy.tenant_domains where tenant_id = ?", a)).isEqualTo(1);
            assertThat(count(c, "select count(*) from tenancy.tenant_domains where tenant_id = ?", b)).isZero();
            return null;
        });
    }

    @Test
    void appRoleCannotWriteRowsOfAnotherTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        assertThat(sqlState(() -> db.inTx("rbac_app", a, c ->
                exec(c, "insert into tenancy.tenant_domains (tenant_id, domain) values (?, ?)",
                        b, "x-" + rand() + ".com"))))
                .isEqualTo("42501");
    }

    @Test
    void malformedTenantIdFailsClosed() {
        UUID a = db.insertTenant();
        insertDomain(a); // an empty table may never evaluate the policy initplan
        assertThat(sqlState(() -> db.inTx("rbac_app", null, c -> {
            one(c, String.class, "select set_config('app.tenant_id', 'not-a-uuid', true)");
            return count(c, "select count(*) from tenancy.tenant_domains");
        }))).isEqualTo("22P02");
    }

    @Test
    void tenantContextDoesNotLeakAcrossTransactions() throws Exception {
        UUID a = db.insertTenant();
        // ONE physical connection, two transactions: MigratedDatabase.inTx would open a fresh one each call.
        try (Connection c = db.dataSource().getConnection()) {
            c.setAutoCommit(false);

            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL ROLE rbac_app");
            }
            try (PreparedStatement ps = c.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, a.toString());
                ps.execute();
            }
            assertThat(one(c, UUID.class, "select util.current_tenant_id()")).isEqualTo(a);
            c.commit();

            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL ROLE rbac_app");
            }
            assertThat(one(c, UUID.class, "select util.current_tenant_id()")).isNull();
            c.commit();
        }
    }
}
