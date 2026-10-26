SET LOCAL ROLE rbac_owner;

-- V4: apps. OAuth/OIDC client applications, their secrets, API keys and the
-- per-tenant signing keys. Every table is tenant-scoped. Also wires the
-- application_id columns left open in V3 and adds the pre-tenant API key lookup.

CREATE TABLE apps.applications (
    tenant_id                  uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id                         uuid        NOT NULL DEFAULT util.uuid_v7(),
    client_id                  text        NOT NULL, -- public identifier, unique across all tenants
    name                       text        NOT NULL,
    type                       text        NOT NULL,
    token_endpoint_auth_method text        NOT NULL,
    grant_types                text[]      NOT NULL DEFAULT '{}',
    redirect_uris              text[]      NOT NULL DEFAULT '{}',
    post_logout_redirect_uris  text[]      NOT NULL DEFAULT '{}',
    allowed_origins            text[]      NOT NULL DEFAULT '{}',
    allowed_scopes             text[]      NOT NULL DEFAULT '{}',
    require_pkce               boolean     NOT NULL DEFAULT true,
    access_token_ttl           interval,
    refresh_token_ttl          interval,
    is_first_party             boolean     NOT NULL DEFAULT false,
    enabled                    boolean     NOT NULL DEFAULT true,
    metadata                   jsonb       NOT NULL DEFAULT '{}',
    created_at                 timestamptz NOT NULL DEFAULT now(),
    updated_at                 timestamptz NOT NULL DEFAULT now(),
    deleted_at                 timestamptz,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT applications_client_id_key UNIQUE (client_id),
    CONSTRAINT applications_type_check CHECK (type IN ('web', 'spa', 'native', 'm2m')),
    CONSTRAINT applications_token_endpoint_auth_method_check
        CHECK (token_endpoint_auth_method IN ('client_secret_basic', 'client_secret_post', 'private_key_jwt', 'none'))
);
CREATE TRIGGER applications_set_updated_at
    BEFORE UPDATE ON apps.applications
    FOR EACH ROW EXECUTE FUNCTION util.set_updated_at();
SELECT util.enable_tenant_rls('apps.applications');

-- Several rows per application so a secret can be rotated without downtime.
CREATE TABLE apps.client_secrets (
    tenant_id      uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id             uuid        NOT NULL DEFAULT util.uuid_v7(),
    application_id uuid        NOT NULL,
    secret_hash    text        NOT NULL,
    hint           text,       -- last 4 characters, to tell secrets apart in a UI
    created_at     timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz,
    revoked_at     timestamptz,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, application_id) REFERENCES apps.applications (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX client_secrets_application_idx ON apps.client_secrets (tenant_id, application_id);
SELECT util.enable_tenant_rls('apps.client_secrets');

CREATE TABLE apps.api_keys (
    tenant_id      uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id             uuid        NOT NULL DEFAULT util.uuid_v7(),
    application_id uuid        NOT NULL, -- owner of the key
    name           text        NOT NULL,
    prefix         text        NOT NULL, -- first ~12 characters, used for lookup; unique across all tenants
    key_hash       text        NOT NULL,
    scopes         text[]      NOT NULL DEFAULT '{}',
    created_at     timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz,
    last_used_at   timestamptz,
    revoked_at     timestamptz,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, application_id) REFERENCES apps.applications (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT api_keys_prefix_key UNIQUE (prefix)
);
CREATE INDEX api_keys_application_idx ON apps.api_keys (tenant_id, application_id);
SELECT util.enable_tenant_rls('apps.api_keys');

-- One key set per tenant, so every tenant has its own issuer and JWKS.
CREATE TABLE apps.signing_keys (
    tenant_id             uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id                    uuid        NOT NULL DEFAULT util.uuid_v7(), -- used as the JWT "kid"
    algorithm             text        NOT NULL,
    public_jwk            jsonb       NOT NULL,
    private_key_encrypted bytea       NOT NULL,
    encryption_key_id     text        NOT NULL,
    status                text        NOT NULL DEFAULT 'next',
    created_at            timestamptz NOT NULL DEFAULT now(),
    activated_at          timestamptz,
    retired_at            timestamptz,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT signing_keys_algorithm_check CHECK (algorithm IN ('RS256', 'ES256', 'EdDSA')),
    CONSTRAINT signing_keys_status_check CHECK (status IN ('next', 'active', 'retired'))
);
CREATE UNIQUE INDEX signing_keys_one_active_key ON apps.signing_keys (tenant_id) WHERE status = 'active';
SELECT util.enable_tenant_rls('apps.signing_keys');

-- Sessions and refresh tokens may only point at an application of their own tenant.
-- application_id stays nullable (MATCH SIMPLE skips the check when it is NULL).
ALTER TABLE identity.sessions
    ADD CONSTRAINT sessions_application_fkey
    FOREIGN KEY (tenant_id, application_id) REFERENCES apps.applications (tenant_id, id) ON DELETE CASCADE;
ALTER TABLE identity.refresh_tokens
    ADD CONSTRAINT refresh_tokens_application_fkey
    FOREIGN KEY (tenant_id, application_id) REFERENCES apps.applications (tenant_id, id) ON DELETE CASCADE;
CREATE INDEX sessions_application_idx ON identity.sessions (tenant_id, application_id) WHERE application_id IS NOT NULL;
CREATE INDEX refresh_tokens_application_idx ON identity.refresh_tokens (tenant_id, application_id) WHERE application_id IS NOT NULL;

-- API key -> tenant lookup before the tenant is known. Returns the row for the exact
-- prefix whatever its state; the caller verifies key_hash, expires_at and revoked_at.
-- SECURITY DEFINER runs as rbac_owner, which has its own policy on api_keys.
CREATE FUNCTION apps.resolve_api_key(p_prefix text)
    RETURNS TABLE (tenant_id uuid, api_key_id uuid, application_id uuid,
                   key_hash text, expires_at timestamptz, revoked_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, extensions
AS $$
    SELECT k.tenant_id, k.id, k.application_id, k.key_hash, k.expires_at, k.revoked_at
    FROM apps.api_keys k
    WHERE k.prefix = p_prefix
$$;

REVOKE EXECUTE ON FUNCTION apps.resolve_api_key(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION apps.resolve_api_key(text) TO rbac_app;
