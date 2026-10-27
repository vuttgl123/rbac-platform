-- V11: per-service database roles. identity_app, authz_app and audit_app each hold exactly what rbac_app
-- holds on the schemas their service owns, plus the shared tenant directory and two deliberate exceptions.
-- rbac_app, rbac_admin and rbac_owner keep their privileges unchanged.
-- Part 1 runs as the migration user (needs CREATE ROLE; it also owns the extensions schema, see V1).
-- Part 2 switches to rbac_owner, which owns, and therefore grants on, every platform object.
--
-- The copy below is taken once, now. A later migration that creates a table or function grants it
-- explicitly to the service role that owns it; util.enable_tenant_rls only adds the RLS policies.

-- Roles (NOLOGIN; logins are bootstrapped out of band, scripts/db/bootstrap_login_roles.sql) ----------------
DO $$
DECLARE
    r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['identity_app', 'authz_app', 'audit_app'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN', r);
        END IF;
    END LOOP;
END
$$;

-- Same as rbac_app (V1): extension types and operators (citext's case-insensitive =) resolve the same way in
-- every environment; util.uuid_v7 calls extensions.gen_random_bytes as the invoker.
GRANT USAGE ON SCHEMA extensions TO identity_app, authz_app, audit_app;
ALTER ROLE identity_app SET search_path = "$user", extensions;
ALTER ROLE authz_app SET search_path = "$user", extensions;
ALTER ROLE audit_app SET search_path = "$user", extensions;

-- From here on everything is granted by rbac_owner ------------------------------------------------------------
SET LOCAL ROLE rbac_owner;

-- Shared by every service: the tenant context, id generation and the tenant directory (slug lookup).
GRANT USAGE ON SCHEMA util, tenancy TO identity_app, authz_app, audit_app;
GRANT EXECUTE ON FUNCTION util.current_tenant_id() TO identity_app, authz_app, audit_app;
GRANT EXECUTE ON FUNCTION util.uuid_v7() TO identity_app, authz_app, audit_app;
GRANT SELECT ON tenancy.tenants TO identity_app, authz_app, audit_app;

-- Privilege copy: every schema, table, column and function privilege rbac_app holds in a schema becomes the
-- same privilege for the role of the service owning that schema. Read from the catalogs rather than listed by
-- hand, so revocations made by earlier migrations (V2 tenants columns, V7 read-only changelog, V8 append-only
-- audit) carry over exactly. Grant options are not copied; rbac_app holds none.
DO $$
DECLARE
    v_grant text;
BEGIN
    FOR v_grant IN
        WITH owned (grantee, schema_name) AS (
            VALUES ('identity_app', 'tenancy'), ('identity_app', 'identity'), ('identity_app', 'apps'),
                   ('authz_app', 'authz'),
                   ('audit_app', 'audit')
        ), scope AS (
            SELECT o.grantee, n.oid AS nsp_oid, n.nspname, n.nspacl
            FROM owned o
            JOIN pg_namespace n ON n.nspname = o.schema_name
        )
        SELECT format('GRANT %s ON SCHEMA %I TO %I', a.privilege_type, s.nspname, s.grantee)
        FROM scope s
        CROSS JOIN LATERAL aclexplode(s.nspacl) a
        WHERE a.grantee = 'rbac_app'::regrole
        UNION ALL
        SELECT format('GRANT %s ON TABLE %s TO %I', a.privilege_type, c.oid::regclass, s.grantee)
        FROM scope s
        JOIN pg_class c ON c.relnamespace = s.nsp_oid AND c.relkind IN ('r', 'p')
        CROSS JOIN LATERAL aclexplode(c.relacl) a
        WHERE a.grantee = 'rbac_app'::regrole
        UNION ALL
        SELECT format('GRANT %s (%I) ON TABLE %s TO %I', a.privilege_type, at.attname, c.oid::regclass, s.grantee)
        FROM scope s
        JOIN pg_class c ON c.relnamespace = s.nsp_oid AND c.relkind IN ('r', 'p')
        JOIN pg_attribute at ON at.attrelid = c.oid AND at.attnum > 0 AND NOT at.attisdropped
        CROSS JOIN LATERAL aclexplode(at.attacl) a
        WHERE a.grantee = 'rbac_app'::regrole
        UNION ALL
        SELECT format('GRANT %s ON ROUTINE %s TO %I', a.privilege_type, p.oid::regprocedure, s.grantee)
        FROM scope s
        JOIN pg_proc p ON p.pronamespace = s.nsp_oid
        CROSS JOIN LATERAL aclexplode(p.proacl) a
        WHERE a.grantee = 'rbac_app'::regrole
    LOOP
        EXECUTE v_grant;
    END LOOP;
END
$$;

-- Exception 1: authz.validate_relationship runs as the invoker and checks that a builtin user, group or
-- application exists in the tuple's tenant. authz_app may read only the two key columns; RLS still applies.
GRANT USAGE ON SCHEMA identity, apps TO authz_app;
GRANT SELECT (tenant_id, id) ON identity.users, identity.groups, apps.applications TO authz_app;

-- Exception 2: deleting a user, group or application removes its tuples without identity_app holding any
-- privilege on authz.relationships. Body unchanged from V7; now SECURITY DEFINER (rbac_owner).
-- Safe for the same reason as authz.log_relationship_change (V7): it is only ever an AFTER DELETE trigger, so
-- OLD is a row the caller was allowed (privileges and RLS) to delete, and the DELETE below is limited to
-- OLD.tenant_id. A trigger function cannot be called directly, and creating a trigger with it needs EXECUTE,
-- which only rbac_owner has.
CREATE OR REPLACE FUNCTION authz.delete_entity_relationships() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, extensions
AS $$
DECLARE
    v_type_id int;
    v_id      text := OLD.id::text;
BEGIN
    SELECT rt.id INTO v_type_id
    FROM authz.resource_types rt
    WHERE rt.tenant_id = OLD.tenant_id AND rt.is_builtin AND rt.key = TG_ARGV[0];
    IF v_type_id IS NULL THEN
        RETURN NULL;
    END IF;

    DELETE FROM authz.relationships r
    WHERE r.tenant_id = OLD.tenant_id
      AND ((r.subject_type_id = v_type_id AND r.subject_id = v_id)
        OR (r.object_type_id = v_type_id AND r.object_id = v_id));
    RETURN NULL;
END
$$;

REVOKE EXECUTE ON FUNCTION authz.delete_entity_relationships() FROM PUBLIC;

-- Tenant isolation: every policy that applies to rbac_app (tenant_isolation, and app_select / app_update on
-- tenancy.tenants) applies to the service roles too. Policies give no access by themselves; the grants above do.
DO $$
DECLARE
    p record;
BEGIN
    FOR p IN
        SELECT pol.schemaname, pol.tablename, pol.policyname,
               array_to_string(ARRAY(
                   SELECT quote_ident(r) FROM unnest(pol.roles) AS r
                   UNION ALL
                   SELECT quote_ident(r) FROM unnest(ARRAY['identity_app', 'authz_app', 'audit_app']::name[]) AS r
                   WHERE r <> ALL (pol.roles)), ', ') AS roles
        FROM pg_policies pol
        WHERE 'rbac_app' = ANY (pol.roles)
    LOOP
        EXECUTE format('ALTER POLICY %I ON %I.%I TO %s', p.policyname, p.schemaname, p.tablename, p.roles);
    END LOOP;
END
$$;

-- util.enable_tenant_rls: as in V1, except tenant_isolation also applies to the service roles. Table
-- privileges still go only to rbac_app and rbac_admin; the migration creating a table grants its service role.
CREATE OR REPLACE FUNCTION util.enable_tenant_rls(p_table regclass) RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog, extensions
AS $$
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', p_table);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', p_table);

    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %s', p_table);
    EXECUTE format(
        'CREATE POLICY tenant_isolation ON %s FOR ALL TO rbac_app, identity_app, authz_app, audit_app '
        'USING (tenant_id = (SELECT util.current_tenant_id())) '
        'WITH CHECK (tenant_id = (SELECT util.current_tenant_id()))', p_table);

    EXECUTE format('DROP POLICY IF EXISTS admin_all ON %s', p_table);
    EXECUTE format(
        'CREATE POLICY admin_all ON %s FOR ALL TO rbac_admin USING (true) WITH CHECK (true)', p_table);

    EXECUTE format('DROP POLICY IF EXISTS owner_all ON %s', p_table);
    EXECUTE format(
        'CREATE POLICY owner_all ON %s FOR ALL TO rbac_owner USING (true) WITH CHECK (true)', p_table);

    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %s TO rbac_app, rbac_admin', p_table);
END
$$;
