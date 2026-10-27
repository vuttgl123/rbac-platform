SET LOCAL ROLE rbac_owner;

-- V2: tenancy. Tenants (the non-tenant-scoped root) and their domains.

CREATE TABLE tenancy.tenants (
    id                   uuid        PRIMARY KEY DEFAULT util.uuid_v7(),
    slug                 text        NOT NULL,
    name                 text        NOT NULL,
    status               text        NOT NULL DEFAULT 'active',
    plan                 text        NOT NULL DEFAULT 'standard',
    settings             jsonb       NOT NULL DEFAULT '{}',
    mfa_min              text        NOT NULL DEFAULT 'off',
    authz_model_revision bigint      NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    deleted_at           timestamptz,
    CONSTRAINT tenants_slug_key UNIQUE (slug),
    CONSTRAINT tenants_slug_format CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    CONSTRAINT tenants_status_check CHECK (status IN ('active', 'suspended', 'deleted')),
    -- settings.mfa.required is tenant-managed (absent = 'off'); mfa_min is the operator-owned floor
    -- the tenant's own setting may never drop below (the platform tenant's is 'required', V10).
    CONSTRAINT tenants_mfa_min_check CHECK (mfa_min IN ('off', 'optional', 'required')),
    CONSTRAINT tenants_settings_mfa_check
        CHECK (coalesce(settings #>> '{mfa,required}', 'off') IN ('off', 'optional', 'required')),
    CONSTRAINT tenants_mfa_floor_check
        CHECK (array_position(ARRAY['off', 'optional', 'required'], coalesce(settings #>> '{mfa,required}', 'off'))
               >= array_position(ARRAY['off', 'optional', 'required'], mfa_min))
);

CREATE TRIGGER tenants_set_updated_at
    BEFORE UPDATE ON tenancy.tenants
    FOR EACH ROW EXECUTE FUNCTION util.set_updated_at();

-- tenants has no tenant_id column, so it gets its own policies instead of
-- util.enable_tenant_rls: every tenant is readable (slug lookup at login), and rbac_app may
-- update only the current tenant's row, and only its name and settings (column-level grant):
-- status, plan, slug, mfa_min, authz_model_revision and deleted_at are operator/system-owned.
-- Tenants are created only through tenancy.create_tenant (SECURITY DEFINER, V9); rbac_admin
-- can update and delete them but not insert directly.
ALTER TABLE tenancy.tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.tenants FORCE ROW LEVEL SECURITY;

CREATE POLICY app_select ON tenancy.tenants FOR SELECT TO rbac_app
    USING (true);
CREATE POLICY app_update ON tenancy.tenants FOR UPDATE TO rbac_app
    USING (id = (SELECT util.current_tenant_id()))
    WITH CHECK (id = (SELECT util.current_tenant_id()));
CREATE POLICY admin_all ON tenancy.tenants FOR ALL TO rbac_admin
    USING (true) WITH CHECK (true);
CREATE POLICY owner_all ON tenancy.tenants FOR ALL TO rbac_owner
    USING (true) WITH CHECK (true);

GRANT SELECT ON tenancy.tenants TO rbac_app;
GRANT UPDATE (name, settings) ON tenancy.tenants TO rbac_app;
GRANT SELECT, UPDATE, DELETE ON tenancy.tenants TO rbac_admin;

CREATE TABLE tenancy.tenant_domains (
    tenant_id               uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id                      uuid        NOT NULL DEFAULT util.uuid_v7(),
    domain                  extensions.citext NOT NULL,
    verified_at             timestamptz,
    verification_token_hash text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT tenant_domains_domain_key UNIQUE (domain)
);

SELECT util.enable_tenant_rls('tenancy.tenant_domains');

-- Domain -> tenant lookup before the tenant is known (SSO routing by email suffix).
-- SECURITY DEFINER runs as rbac_owner, which has its own policy on tenant_domains.
CREATE FUNCTION tenancy.resolve_domain(p_domain text) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, extensions
AS $$
    SELECT d.tenant_id
    FROM tenancy.tenant_domains d
    WHERE d.domain = p_domain::extensions.citext
      AND d.verified_at IS NOT NULL
$$;

REVOKE EXECUTE ON FUNCTION tenancy.resolve_domain(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.resolve_domain(text) TO rbac_app;
