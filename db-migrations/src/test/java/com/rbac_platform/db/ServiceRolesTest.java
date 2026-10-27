package com.rbac_platform.db;

import static com.rbac_platform.testsupport.Sql.count;
import static com.rbac_platform.testsupport.Sql.errorMessage;
import static com.rbac_platform.testsupport.Sql.exec;
import static com.rbac_platform.testsupport.Sql.one;
import static com.rbac_platform.testsupport.Sql.sqlState;
import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.testsupport.MigratedDatabase;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * V11: identity_app, authz_app and audit_app each hold exactly what rbac_app holds on the schemas their service
 * owns, plus the shared tenant directory and the two documented exceptions; RLS applies to all of them.
 */
class ServiceRolesTest {

    private static final List<String> SERVICE_ROLES = List.of("identity_app", "authz_app", "audit_app");
    private static final List<String> APP_ROLES = List.of("rbac_app", "identity_app", "authz_app", "audit_app");
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private static final String INSERT_EVENT =
            "insert into audit.events (tenant_id, actor_type, action, outcome, occurred_at) "
                    + "values (?, 'system', 'v11.probe', 'success', now() + ?::interval)";

    /** Every ACL entry granted directly to role {@code ?}, as "kind object privilege", over the whole database. */
    private static final String ACL_ENTRIES = """
            select 'schema ' || n.nspname || ' ' || a.privilege_type
            from pg_namespace n, aclexplode(n.nspacl) a where a.grantee = ?::regrole
            union all
            select 'table ' || c.oid::regclass::text || ' ' || a.privilege_type
            from pg_class c, aclexplode(c.relacl) a where a.grantee = ?::regrole
            union all
            select 'column ' || c.oid::regclass::text || '.' || at.attname || ' ' || a.privilege_type
            from pg_attribute at join pg_class c on c.oid = at.attrelid, aclexplode(at.attacl) a
            where a.grantee = ?::regrole
            union all
            select 'function ' || p.oid::regprocedure::text || ' ' || a.privilege_type
            from pg_proc p, aclexplode(p.proacl) a where a.grantee = ?::regrole
            union all
            select 'type ' || t.oid::regtype::text || ' ' || a.privilege_type
            from pg_type t, aclexplode(t.typacl) a where a.grantee = ?::regrole
            union all
            select 'database ' || d.datname || ' ' || a.privilege_type
            from pg_database d, aclexplode(d.datacl) a where a.grantee = ?::regrole
            union all
            select 'default ' || d.defaclobjtype::text || ' ' || a.privilege_type
            from pg_default_acl d, aclexplode(d.defaclacl) a where a.grantee = ?::regrole
            """;

    /** rbac_app's ACL entries on objects in the given schemas, in the same format as {@link #ACL_ENTRIES}. */
    private static final String RBAC_APP_ENTRIES_IN = """
            select 'schema ' || n.nspname || ' ' || a.privilege_type
            from pg_namespace n, aclexplode(n.nspacl) a
            where a.grantee = 'rbac_app'::regrole and n.nspname = any (?::text[])
            union all
            select 'table ' || c.oid::regclass::text || ' ' || a.privilege_type
            from pg_class c join pg_namespace n on n.oid = c.relnamespace, aclexplode(c.relacl) a
            where a.grantee = 'rbac_app'::regrole and n.nspname = any (?::text[])
            union all
            select 'column ' || c.oid::regclass::text || '.' || at.attname || ' ' || a.privilege_type
            from pg_attribute at join pg_class c on c.oid = at.attrelid join pg_namespace n on n.oid = c.relnamespace,
                 aclexplode(at.attacl) a
            where a.grantee = 'rbac_app'::regrole and n.nspname = any (?::text[])
            union all
            select 'function ' || p.oid::regprocedure::text || ' ' || a.privilege_type
            from pg_proc p join pg_namespace n on n.oid = p.pronamespace, aclexplode(p.proacl) a
            where a.grantee = 'rbac_app'::regrole and n.nspname = any (?::text[])
            """;

    /** What every service role gets besides its own schemas. */
    private static final List<String> SHARED = List.of(
            "schema extensions USAGE",
            "schema util USAGE",
            "schema tenancy USAGE",
            "function util.current_tenant_id() EXECUTE",
            "function util.uuid_v7() EXECUTE",
            "table tenancy.tenants SELECT");

    /** Exception 1: authz_app may check that a user, group or application exists, nothing more. */
    private static final List<String> AUTHZ_ENTITY_IDS = List.of(
            "schema identity USAGE",
            "schema apps USAGE",
            "column identity.users.tenant_id SELECT",
            "column identity.users.id SELECT",
            "column identity.groups.tenant_id SELECT",
            "column identity.groups.id SELECT",
            "column apps.applications.tenant_id SELECT",
            "column apps.applications.id SELECT");

    private final MigratedDatabase db = MigratedDatabase.get();

    private static List<String> strings(Connection c, String sql, Object... args) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static List<String> aclEntries(Connection c, String role) throws SQLException {
        return strings(c, ACL_ENTRIES, role, role, role, role, role, role, role);
    }

    private static List<String> rbacAppEntriesIn(Connection c, String... schemas) throws SQLException {
        Array s = c.createArrayOf("text", schemas);
        return strings(c, RBAC_APP_ENTRIES_IN, s, s, s, s);
    }

    private static int builtinType(Connection c, UUID t, String key) {
        return one(c, Integer.class, "select id from authz.resource_types where tenant_id = ? and key = ?", t, key);
    }

    private static int tenantMember(Connection c, UUID t) {
        return one(c, Integer.class,
                "select r.id from authz.relations r join authz.resource_types rt "
                + "on rt.tenant_id = r.tenant_id and rt.id = r.resource_type_id "
                + "where r.tenant_id = ? and rt.key = 'tenant' and r.key = 'member'", t);
    }

    /** Inserts tenant:t#member@user:userId on an open connection and returns the tuple id. */
    private static long putTenantMember(Connection c, UUID t, String userId) {
        return one(c, Long.class,
                "insert into authz.relationships (tenant_id, object_type_id, object_id, relation_id, "
                + "subject_type_id, subject_id) values (?, ?, ?, ?, ?, ?) returning id",
                t, builtinType(c, t, "tenant"), t.toString(), tenantMember(c, t), builtinType(c, t, "user"), userId);
    }

    private static String email() {
        return "u-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void serviceRolesExistWithoutLogin() {
        db.inTx(null, null, c -> {
            String rbacAppSettings = one(c, String.class, "select setconfig::text from pg_db_role_setting "
                    + "where setrole = 'rbac_app'::regrole and setdatabase = 0");
            assertThat(rbacAppSettings).contains("search_path");
            for (String role : SERVICE_ROLES) {
                assertThat(count(c, "select count(*) from pg_roles where rolname = ? and not rolcanlogin "
                        + "and not rolsuper and not rolbypassrls and not rolcreaterole and not rolcreatedb "
                        + "and not rolreplication", role)).as(role).isEqualTo(1);
                assertThat(one(c, String.class, "select setconfig::text from pg_db_role_setting "
                        + "where setrole = ?::regrole and setdatabase = 0", role))
                        .as("%s search_path", role).isEqualTo(rbacAppSettings);
                // Members of no role: everything they can do comes from their own grants.
                assertThat(count(c, "select count(*) from pg_auth_members where member = ?::regrole", role))
                        .as("%s memberships", role).isZero();
            }
            return null;
        });
    }

    @Test
    void privilegesAreExactlyRbacAppsOnOwnedSchemasPlusSharedAndExceptions() {
        db.inTx(null, null, c -> {
            List<String> identityExpected = new ArrayList<>(SHARED);
            identityExpected.addAll(rbacAppEntriesIn(c, "tenancy", "identity", "apps"));
            List<String> authzExpected = new ArrayList<>(SHARED);
            authzExpected.addAll(rbacAppEntriesIn(c, "authz"));
            authzExpected.addAll(AUTHZ_ENTITY_IDS);
            List<String> auditExpected = new ArrayList<>(SHARED);
            auditExpected.addAll(rbacAppEntriesIn(c, "audit"));

            // Guard against a vacuous pass: the copied sets contain the known privileges of each schema.
            assertThat(identityExpected).contains("column tenancy.tenants.name UPDATE",
                    "column tenancy.tenants.settings UPDATE", "table identity.users DELETE",
                    "table apps.client_secrets INSERT", "function tenancy.resolve_domain(text) EXECUTE",
                    "function identity.find_user_by_email(text) EXECUTE", "function apps.resolve_api_key(text) EXECUTE")
                    .doesNotContain("table tenancy.tenants UPDATE");
            assertThat(authzExpected).contains("table authz.relationships INSERT",
                    "table authz.relationship_changes SELECT").doesNotContain("table authz.relationship_changes INSERT");
            assertThat(auditExpected).contains("table audit.events INSERT", "table audit.events SELECT")
                    .doesNotContain("table audit.events UPDATE", "table audit.events DELETE");

            // Sets: tenancy USAGE and tenants SELECT are both shared and copied for identity_app.
            assertThat(aclEntries(c, "identity_app")).containsExactlyInAnyOrderElementsOf(new TreeSet<>(identityExpected));
            assertThat(aclEntries(c, "authz_app")).containsExactlyInAnyOrderElementsOf(new TreeSet<>(authzExpected));
            assertThat(aclEntries(c, "audit_app")).containsExactlyInAnyOrderElementsOf(new TreeSet<>(auditExpected));
            return null;
        });
    }

    @Test
    void identityRoleManagesItsSchemasWithinItsTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        UUID bUser = db.insertUser(b, email());
        String mail = email();

        UUID aUser = db.inTx("identity_app", a, c -> one(c, UUID.class,
                "insert into identity.users (tenant_id, email, status) values (?, ?, 'active') returning id", a, mail));
        db.inTx("identity_app", a, c -> {
            assertThat(count(c, "select count(*) from identity.users where id = ?", aUser)).isEqualTo(1);
            assertThat(count(c, "select count(*) from identity.users where id = ?", bUser)).isZero();
            assertThat(count(c, "select count(*) from identity.users where tenant_id = ?", b)).isZero();
            assertThat(exec(c, "update tenancy.tenants set name = 'x' where id = ?", a)).isEqualTo(1);
            // Case-insensitive citext lookup through the SECURITY DEFINER login function.
            assertThat(one(c, UUID.class, "select id from identity.find_user_by_email(?)", mail.toUpperCase()))
                    .isEqualTo(aUser);
            return null;
        });
        assertThat(sqlState(() -> db.inTx("identity_app", a, c ->
                exec(c, "update tenancy.tenants set status = 'suspended' where id = ?", a))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        // RLS still limits the column grant to the current tenant's row.
        int otherTenantRows = db.inTx("identity_app", a, c ->
                exec(c, "update tenancy.tenants set name = 'y' where id = ?", b));
        assertThat(otherTenantRows).isZero();
    }

    @Test
    void identityRoleCannotTouchAuthzOrAudit() {
        UUID a = db.insertTenant();
        assertThat(sqlState(() -> db.inTx("identity_app", a, c -> strings(c, "select * from authz.relations"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("identity_app", a, c ->
                exec(c, "insert into authz.relationships (tenant_id, object_type_id, object_id, relation_id, "
                        + "subject_type_id, subject_id) values (?, 1, 'x', 1, 1, 'y')", a))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("identity_app", a, c -> strings(c, "select * from audit.events"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    void authzRoleSeesOnlyIdsOfIdentityEntities() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        UUID aUser = db.insertUser(a, email());
        db.insertUser(b, email());

        db.inTx("authz_app", a, c -> {
            assertThat(strings(c, "select tenant_id::text || '/' || id::text from identity.users"))
                    .containsExactly(a + "/" + aUser);
            return null;
        });
        assertThat(sqlState(() -> db.inTx("authz_app", a, c -> strings(c, "select email from identity.users"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("authz_app", a, c ->
                strings(c, "select * from identity.password_credentials"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("authz_app", a, c ->
                exec(c, "insert into identity.users (tenant_id, email) values (?, ?)", a, email()))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("authz_app", a, c ->
                strings(c, "select * from apps.client_secrets"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    void authzRoleWritesRelationshipsButStillValidatesEntities() {
        UUID a = db.insertTenant();
        UUID u = db.insertUser(a, email());

        long id = db.inTx("authz_app", a, c -> putTenantMember(c, a, u.toString()));
        db.inTx("authz_app", a, c -> {
            assertThat(count(c, "select count(*) from authz.relationships where id = ?", id)).isEqualTo(1);
            assertThat(count(c, "select count(*) from authz.relationship_changes where relationship_id = ?", id))
                    .isEqualTo(1);
            return null;
        });
        assertThat(errorMessage(() -> db.inTx("authz_app", a, c -> putTenantMember(c, a, UUID.randomUUID().toString()))))
                .contains("builtin_entity_missing");
    }

    @Test
    void deletingAUserAsIdentityRoleRemovesItsRelationships() {
        UUID a = db.insertTenant();
        UUID u = db.insertUser(a, email());
        long id = db.inTx(null, null, c -> putTenantMember(c, a, u.toString()));

        int deleted = db.inTx("identity_app", a, c ->
                exec(c, "delete from identity.users where tenant_id = ? and id = ?", a, u));
        assertThat(deleted).isEqualTo(1);
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from authz.relationships where tenant_id = ? and id = ?", a, id))
                    .isZero();
            assertThat(count(c, "select count(*) from authz.relationship_changes "
                    + "where tenant_id = ? and relationship_id = ? and op = 'delete'", a, id)).isEqualTo(1);
            return null;
        });
    }

    @Test
    void auditRoleWritesAndReadsOnlyAuditEvents() {
        UUID a = db.insertTenant();
        db.inTx("audit_app", a, c -> {
            exec(c, INSERT_EVENT, a, "0 seconds");
            assertThat(count(c, "select count(*) from audit.events where action = 'v11.probe'")).isEqualTo(1);
            return null;
        });
        assertThat(sqlState(() -> db.inTx("audit_app", a, c -> exec(c, "update audit.events set outcome = 'failure'"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("audit_app", a, c -> exec(c, "delete from audit.events"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("audit_app", a, c -> strings(c, "select * from identity.users"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(() -> db.inTx("audit_app", a, c -> strings(c, "select * from authz.relationships"))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    void auditRoleWritesIntoPartitionsCreatedLater() {
        UUID a = db.insertTenant();
        String partitionsOfEvents = "select inhrelid::regclass::text from pg_inherits "
                + "where inhparent = 'audit.events'::regclass";
        List<String> before = db.inTx(null, null, c -> strings(c, partitionsOfEvents));
        try {
            db.inTx(null, null, c -> exec(c, "select util.ensure_monthly_partitions('audit.events'::regclass, 6)"));
            db.inTx("audit_app", a, c -> exec(c, INSERT_EVENT, a, "5 months"));
            db.inTx(null, null, c -> {
                // Landed in a monthly partition, not in the DEFAULT safety net.
                assertThat(one(c, String.class, "select tableoid::regclass::text from audit.events "
                        + "where tenant_id = ? and action = 'v11.probe'", a)).startsWith("audit.events_p");
                return null;
            });
        } finally {
            // Other tests (AuditTest) rely on months beyond the pre-created three landing in DEFAULT.
            db.inTx(null, null, c -> {
                for (String partition : strings(c, partitionsOfEvents)) {
                    if (!before.contains(partition)) {
                        exec(c, "drop table " + partition);
                    }
                }
                return null;
            });
        }
    }

    @Test
    void allServiceRolesReadTheTenantDirectory() {
        UUID a = db.insertTenant();
        for (String role : SERVICE_ROLES) {
            db.inTx(role, a, c -> {
                assertThat(one(c, UUID.class, "select id from tenancy.tenants where id = ?", a)).as(role).isEqualTo(a);
                assertThat(one(c, UUID.class, "select util.uuid_v7()")).as(role).isNotNull();
                assertThat(one(c, UUID.class, "select util.current_tenant_id()")).as(role).isEqualTo(a);
                return null;
            });
        }
    }

    @Test
    void tablesProtectedLaterIncludeServiceRoles() {
        db.inTx("rbac_owner", null, c -> {
            exec(c, "create table util.v11_probe (tenant_id uuid not null, id int)");
            exec(c, "select util.enable_tenant_rls('util.v11_probe')");
            List<String> roles = strings(c, "select unnest(roles)::text from pg_policies "
                    + "where schemaname = 'util' and tablename = 'v11_probe' and policyname = 'tenant_isolation'");
            assertThat(roles).containsExactlyInAnyOrderElementsOf(APP_ROLES);
            // Table privileges still go only to rbac_app and rbac_admin; owning migrations grant service roles.
            for (String role : SERVICE_ROLES) {
                assertThat(count(c, "select count(*) from pg_class where oid = 'util.v11_probe'::regclass "
                        + "and has_table_privilege(?, oid, 'SELECT,INSERT,UPDATE,DELETE')", role)).as(role).isZero();
            }
            exec(c, "drop table util.v11_probe");
            return null;
        });
    }

    @Test
    void everyPolicyOfRbacAppAlsoCoversTheServiceRoles() {
        db.inTx(null, null, c -> {
            List<String> rbacAppPolicies = strings(c, "select schemaname || '.' || tablename || ' ' || policyname "
                    + "from pg_policies where 'rbac_app' = any (roles)");
            assertThat(rbacAppPolicies).contains("tenancy.tenants app_select", "tenancy.tenants app_update",
                    "identity.users tenant_isolation", "authz.relationships tenant_isolation",
                    "audit.events tenant_isolation");
            assertThat(strings(c, "select schemaname || '.' || tablename || ' ' || policyname from pg_policies "
                    + "where 'rbac_app' = any (roles) and not (roles @> array['identity_app', 'authz_app', 'audit_app']::name[])"))
                    .isEmpty();
            // Only those policies name the service roles.
            assertThat(strings(c, "select schemaname || '.' || tablename || ' ' || policyname from pg_policies "
                    + "where roles && array['identity_app', 'authz_app', 'audit_app']::name[] "
                    + "and not ('rbac_app' = any (roles))")).isEmpty();
            return null;
        });
    }

    @Test
    void newRolesAreNotReachableByApiRolesOrPublic() {
        db.inTx(null, null, c -> {
            for (String api : List.of("anon", "authenticated", "service_role")) {
                for (String role : SERVICE_ROLES) {
                    assertThat(one(c, Boolean.class, "select pg_has_role(?, ?, 'MEMBER')", api, role))
                            .as("%s member of %s", api, role).isFalse();
                }
            }
            assertThat(one(c, Boolean.class,
                    "select has_function_privilege('public', 'authz.delete_entity_relationships()', 'EXECUTE')"))
                    .isFalse();
            assertThat(one(c, Boolean.class,
                    "select prosecdef from pg_proc where oid = 'authz.delete_entity_relationships()'::regprocedure"))
                    .isTrue();
            assertThat(one(c, String.class, "select proconfig::text from pg_proc "
                    + "where oid = 'authz.delete_entity_relationships()'::regprocedure"))
                    .isEqualTo("{\"search_path=pg_catalog, extensions\"}");
            return null;
        });
    }
}
