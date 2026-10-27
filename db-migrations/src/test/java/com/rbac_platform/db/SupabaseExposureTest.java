package com.rbac_platform.db;

import static com.rbac_platform.testsupport.Sql.count;
import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.testsupport.MigratedDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Supabase's Data API connects as anon / authenticated / service_role. Nothing the platform owns (nor the Flyway
 * history schema) may be reachable by them, and the application roles must not be able to run operator-only
 * functions.
 */
class SupabaseExposureTest {

    private static final String SCHEMAS = "'tenancy','identity','apps','authz','audit','util'";
    private static final List<String> API_ROLES = List.of("anon", "authenticated", "service_role");

    private final MigratedDatabase db = MigratedDatabase.get();

    private static List<String> strings(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    void apiRolesCannotTouchAnyPlatformObject() {
        db.inTx(null, null, c -> {
            // Tables, partitions, views, materialized views, sequences.
            String relations = "select c.oid::regclass::text || ':' || c.relkind::text from pg_class c "
                    + "join pg_namespace n on n.oid = c.relnamespace "
                    + "where n.nspname in (" + SCHEMAS + ") and c.relkind in ('r','p','v','m','S')";
            List<String> scanned = strings(c, relations);
            // Guard against a vacuous pass: the scan must see real tables, partitions and sequences.
            assertThat(scanned).isNotEmpty().anyMatch(s -> s.startsWith("tenancy.tenants:r"))
                    .anyMatch(s -> s.endsWith(":p")).anyMatch(s -> s.endsWith(":S"));
            assertThat(scanned.size()).isGreaterThan(20);

            List<String> violations = new ArrayList<>();
            for (String role : API_ROLES) {
                violations.addAll(strings(c, "select '" + role + " " + "' || c.oid::regclass::text "
                        + "from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                        + "where n.nspname in (" + SCHEMAS + ") and c.relkind in ('r','p','v','m') and ("
                        + "has_table_privilege('" + role + "', c.oid, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
                        + "or has_any_column_privilege('" + role + "', c.oid, 'SELECT,INSERT,UPDATE,REFERENCES'))"));
                violations.addAll(strings(c, "select '" + role + " " + "' || c.oid::regclass::text "
                        + "from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                        + "where n.nspname in (" + SCHEMAS + ") and c.relkind = 'S' "
                        // CASE keeps the planner from evaluating the function on non-sequences.
                        + "and case when c.relkind = 'S' then "
                        + "has_sequence_privilege('" + role + "', c.oid, 'USAGE,SELECT,UPDATE') end"));
                violations.addAll(strings(c, "select '" + role + " schema ' || n.nspname from pg_namespace n "
                        + "where n.nspname in (" + SCHEMAS + ") "
                        + "and has_schema_privilege('" + role + "', n.oid, 'USAGE,CREATE')"));
            }
            assertThat(violations).as("objects reachable by Supabase API roles").isEmpty();
            return null;
        });
    }

    @Test
    void apiRolesExistAndCannotUseFlywaySchema() {
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from pg_namespace where nspname = 'flyway'")).isEqualTo(1);
            for (String role : API_ROLES) {
                assertThat(count(c, "select count(*) from pg_roles where rolname = ?", role)).as(role).isEqualTo(1);
                assertThat(count(c, "select count(*) from pg_namespace n where n.nspname = 'flyway' "
                        + "and has_schema_privilege(?, n.oid, 'USAGE,CREATE')", role))
                        .as("%s USAGE/CREATE on schema flyway", role).isZero();
            }
            return null;
        });
    }

    @Test
    void apiRolesCannotExecuteAnyPlatformFunction() {
        db.inTx(null, null, c -> {
            List<String> definers = strings(c, "select p.oid::regprocedure::text from pg_proc p "
                    + "join pg_namespace n on n.oid = p.pronamespace "
                    + "where n.nspname in (" + SCHEMAS + ") and p.prosecdef");
            assertThat(definers).isNotEmpty().contains("tenancy.resolve_domain(text)",
                    "apps.resolve_api_key(text)", "tenancy.create_tenant(text,text,text,text,timestamp with time zone,uuid)",
                    "authz.seed_builtin_model(uuid)", "util.create_monthly_partition(regclass,date)");

            List<String> all = strings(c, "select p.oid::regprocedure::text from pg_proc p "
                    + "join pg_namespace n on n.oid = p.pronamespace where n.nspname in (" + SCHEMAS + ")");
            assertThat(all.size()).isGreaterThan(definers.size());

            List<String> violations = new ArrayList<>();
            for (String role : API_ROLES) {
                for (String fn : strings(c, "select p.oid::regprocedure::text from pg_proc p "
                        + "join pg_namespace n on n.oid = p.pronamespace "
                        + "where n.nspname in (" + SCHEMAS + ") "
                        + "and has_function_privilege('" + role + "', p.oid, 'EXECUTE')")) {
                    violations.add(role + " " + fn);
                }
            }
            assertThat(violations).as("functions executable by Supabase API roles").isEmpty();
            return null;
        });
    }

    @Test
    void nothingCreatedInPublicSchema() {
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                    + "where n.nspname = 'public' and c.relowner = 'rbac_owner'::regrole")).isZero();
            assertThat(count(c, "select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                    + "where n.nspname = 'public' and p.proowner = 'rbac_owner'::regrole")).isZero();
            return null;
        });
    }

    @Test
    void appRoleCannotExecuteAdminOnlyFunctions() {
        db.inTx(null, null, c -> {
            List<String> names = List.of("create_tenant", "create_monthly_partition", "ensure_monthly_partitions",
                    "drop_partitions_older_than", "seed_builtin_model", "enable_tenant_rls", "create_outbox",
                    "create_batch_repository");
            for (String name : names) {
                List<String> signatures = strings(c, "select p.oid::regprocedure::text from pg_proc p "
                        + "join pg_namespace n on n.oid = p.pronamespace "
                        + "where n.nspname in ('tenancy','authz','util') and p.proname = '" + name + "'");
                assertThat(signatures).as("signatures of " + name).hasSize(1);
                assertThat(count(c, "select count(*) from pg_proc p where p.oid = ?::regprocedure "
                        + "and has_function_privilege('rbac_app', p.oid, 'EXECUTE')", signatures.get(0)))
                        .as("rbac_app EXECUTE on " + signatures.get(0)).isZero();
            }
            // Positive control: the privilege check is not trivially false.
            assertThat(count(c, "select count(*) from pg_proc where oid = 'tenancy.resolve_domain(text)'::regprocedure "
                    + "and has_function_privilege('rbac_app', oid, 'EXECUTE')")).isEqualTo(1);
            assertThat(count(c, "select count(*) from pg_proc where oid = "
                    + "'tenancy.create_tenant(text,text,text,text,timestamptz,uuid)'::regprocedure "
                    + "and has_function_privilege('rbac_admin', oid, 'EXECUTE')")).isEqualTo(1);
            return null;
        });
    }
}
