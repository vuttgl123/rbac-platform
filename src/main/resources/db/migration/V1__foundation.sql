-- V1: foundation. Roles, extensions, platform schemas and shared util functions.
-- Part 1 runs as the migration user (needs CREATE ROLE / CREATE EXTENSION).
-- Part 2 switches to rbac_owner so that every platform object is owned by it.

-- Roles (NOLOGIN; login roles are created out of band) -------------------------
DO $$
DECLARE
    r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['rbac_owner', 'rbac_app', 'rbac_admin'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN', r);
        END IF;
    END LOOP;
END
$$;

-- Lets the migration user SET ROLE rbac_owner and later grant on its behalf.
GRANT rbac_owner TO CURRENT_USER;

DO $$
BEGIN
    EXECUTE format('GRANT CREATE ON DATABASE %I TO rbac_owner', current_database());
END
$$;

-- Extensions --------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA extensions;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;
GRANT USAGE ON SCHEMA extensions TO rbac_owner, rbac_app, rbac_admin;

-- Sessions that log in as the application roles resolve extension types and operators (citext's
-- case-insensitive =) the same way in every environment, without schema-qualifying each one.
ALTER ROLE rbac_app SET search_path = "$user", extensions;
ALTER ROLE rbac_admin SET search_path = "$user", extensions;

-- From here on everything is created by rbac_owner ------------------------------
SET LOCAL ROLE rbac_owner;

CREATE SCHEMA tenancy  AUTHORIZATION rbac_owner;
CREATE SCHEMA identity AUTHORIZATION rbac_owner;
CREATE SCHEMA apps     AUTHORIZATION rbac_owner;
CREATE SCHEMA authz    AUTHORIZATION rbac_owner;
CREATE SCHEMA audit    AUTHORIZATION rbac_owner;
CREATE SCHEMA util     AUTHORIZATION rbac_owner;

GRANT USAGE ON SCHEMA tenancy, identity, apps, authz, audit, util TO rbac_app, rbac_admin;

-- Supabase API roles must never reach platform data. They do not exist on plain
-- Postgres, hence the conditional.
DO $$
DECLARE
    r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['anon', 'authenticated', 'service_role'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format(
                'REVOKE ALL ON SCHEMA tenancy, identity, apps, authz, audit, util FROM %I', r);
        END IF;
    END LOOP;
END
$$;

-- Functions created by rbac_owner from now on are not executable by PUBLIC;
-- grants must be explicit.
ALTER DEFAULT PRIVILEGES FOR ROLE rbac_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- util.uuid_v7: 48-bit unix epoch ms | version 7 | variant 10 | random ----------
CREATE FUNCTION util.uuid_v7() RETURNS uuid
    LANGUAGE sql VOLATILE PARALLEL SAFE
    SET search_path = pg_catalog, extensions
AS $$
    SELECT encode(
        set_byte(set_byte(b, 6, (get_byte(b, 6) & 15) | 112), 8, (get_byte(b, 8) & 63) | 128),
        'hex')::uuid
    FROM (
        SELECT overlay(
            extensions.gen_random_bytes(16)
            PLACING substring(int8send((extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3)
            FROM 1 FOR 6) AS b
    ) AS s
$$;

-- util.current_tenant_id: tenant bound to the transaction, NULL when unset.
-- A malformed value raises (fails closed) instead of matching nothing silently.
CREATE FUNCTION util.current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
    SET search_path = pg_catalog, extensions
AS $$
    SELECT nullif(current_setting('app.tenant_id', true), '')::uuid
$$;

-- util.set_updated_at: BEFORE UPDATE trigger function.
CREATE FUNCTION util.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, extensions
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END
$$;

-- util.enable_tenant_rls: standard RLS setup for a table with a tenant_id column.
CREATE FUNCTION util.enable_tenant_rls(p_table regclass) RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog, extensions
AS $$
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', p_table);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', p_table);

    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %s', p_table);
    EXECUTE format(
        'CREATE POLICY tenant_isolation ON %s FOR ALL TO rbac_app '
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

GRANT EXECUTE ON FUNCTION util.current_tenant_id() TO rbac_app, rbac_admin;
-- Needed by column defaults and policies evaluated as rbac_app / rbac_admin.
GRANT EXECUTE ON FUNCTION util.uuid_v7() TO rbac_app, rbac_admin;
