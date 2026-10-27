SET LOCAL ROLE rbac_owner;

-- V3: identity. Users, credentials, MFA, SSO identity providers, groups,
-- sessions, refresh tokens and one-time tokens. Every table is tenant-scoped.
-- Group membership is NOT stored here; it lives in authz tuples.

CREATE TABLE identity.users (
    tenant_id          uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id                 uuid        NOT NULL DEFAULT util.uuid_v7(),
    email              extensions.citext,
    email_verified_at  timestamptz,
    username           extensions.citext,
    phone              text,
    phone_verified_at  timestamptz,
    display_name       text,
    given_name         text,
    family_name        text,
    locale             text,
    timezone           text,
    avatar_url         text,
    status             text        NOT NULL DEFAULT 'active',
    external_id        text,
    attributes         jsonb       NOT NULL DEFAULT '{}',
    last_login_at      timestamptz,
    failed_login_count integer     NOT NULL DEFAULT 0,
    locked_until       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    deleted_at         timestamptz,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT users_status_check CHECK (status IN ('invited', 'active', 'locked', 'disabled')),
    CONSTRAINT users_identifier_check CHECK (email IS NOT NULL OR username IS NOT NULL OR phone IS NOT NULL),
    CONSTRAINT users_failed_login_count_check CHECK (failed_login_count >= 0)
);

CREATE UNIQUE INDEX users_tenant_email_key ON identity.users (tenant_id, email) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX users_tenant_username_key ON identity.users (tenant_id, username) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX users_tenant_phone_key ON identity.users (tenant_id, phone) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX users_tenant_external_id_key ON identity.users (tenant_id, external_id) WHERE external_id IS NOT NULL;

CREATE TRIGGER users_set_updated_at
    BEFORE UPDATE ON identity.users
    FOR EACH ROW EXECUTE FUNCTION util.set_updated_at();
SELECT util.enable_tenant_rls('identity.users');

-- Login lookups by email / username within the current tenant (app.tenant_id); no context, no rows.
-- SECURITY DEFINER with extensions on the search_path: the comparison is always citext (case-
-- insensitive) whatever the caller's search_path, and it runs under rbac_owner's owner_all policy, so
-- citext's non-LEAKPROOF = is not held behind an RLS qual and the unique (tenant_id, email|username)
-- index is used. Soft-deleted users are never returned.
CREATE FUNCTION identity.find_user_by_email(p_email text) RETURNS SETOF identity.users
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, extensions
AS $$
    SELECT u.*
    FROM identity.users u
    WHERE u.tenant_id = util.current_tenant_id()
      AND u.deleted_at IS NULL
      AND u.email = p_email::extensions.citext
$$;

CREATE FUNCTION identity.find_user_by_username(p_username text) RETURNS SETOF identity.users
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, extensions
AS $$
    SELECT u.*
    FROM identity.users u
    WHERE u.tenant_id = util.current_tenant_id()
      AND u.deleted_at IS NULL
      AND u.username = p_username::extensions.citext
$$;

REVOKE EXECUTE ON FUNCTION identity.find_user_by_email(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION identity.find_user_by_username(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity.find_user_by_email(text) TO rbac_app, rbac_admin;
GRANT EXECUTE ON FUNCTION identity.find_user_by_username(text) TO rbac_app, rbac_admin;

-- One row per user, kept apart so a hash is never read together with the user row.
CREATE TABLE identity.password_credentials (
    tenant_id     uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    user_id       uuid        NOT NULL,
    password_hash text        NOT NULL, -- Argon2id, PHC string
    must_change   boolean     NOT NULL DEFAULT false,
    changed_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES identity.users (tenant_id, id) ON DELETE CASCADE
);
SELECT util.enable_tenant_rls('identity.password_credentials');

CREATE TABLE identity.password_history (
    tenant_id     uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id            uuid        NOT NULL DEFAULT util.uuid_v7(),
    user_id       uuid        NOT NULL,
    password_hash text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES identity.users (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX password_history_user_idx ON identity.password_history (tenant_id, user_id, created_at DESC);
SELECT util.enable_tenant_rls('identity.password_history');

CREATE TABLE identity.mfa_factors (
    tenant_id         uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id                uuid        NOT NULL DEFAULT util.uuid_v7(),
    user_id           uuid        NOT NULL,
    type              text        NOT NULL,
    label             text,
    secret_encrypted  bytea       NOT NULL,
    encryption_key_id text        NOT NULL,
    last_used_step    bigint,     -- blocks reuse of the same TOTP code
    verified_at       timestamptz, -- NULL: enrolment not finished
    last_used_at      timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES identity.users (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT mfa_factors_type_check CHECK (type IN ('totp'))
);
CREATE INDEX mfa_factors_user_idx ON identity.mfa_factors (tenant_id, user_id);
SELECT util.enable_tenant_rls('identity.mfa_factors');

CREATE TABLE identity.mfa_recovery_codes (
    tenant_id  uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id         uuid        NOT NULL DEFAULT util.uuid_v7(),
    user_id    uuid        NOT NULL,
    code_hash  text        NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES identity.users (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX mfa_recovery_codes_user_idx ON identity.mfa_recovery_codes (tenant_id, user_id);
SELECT util.enable_tenant_rls('identity.mfa_recovery_codes');

CREATE TABLE identity.identity_providers (
    tenant_id               uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id                      uuid        NOT NULL DEFAULT util.uuid_v7(),
    type                    text        NOT NULL,
    name                    text        NOT NULL,
    enabled                 boolean     NOT NULL DEFAULT true,
    issuer                  text,
    discovery_url           text,
    client_id               text,
    client_secret_encrypted bytea,
    encryption_key_id       text,
    scopes                  text[]      NOT NULL DEFAULT ARRAY['openid', 'email', 'profile'],
    claim_mapping           jsonb       NOT NULL DEFAULT '{}',
    allowed_domains         text[]      NOT NULL DEFAULT '{}',
    jit_provisioning        boolean     NOT NULL DEFAULT false,
    config                  jsonb       NOT NULL DEFAULT '{}',
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT identity_providers_type_check CHECK (type IN ('oidc', 'saml')),
    CONSTRAINT identity_providers_secret_key_check
        CHECK (client_secret_encrypted IS NULL OR encryption_key_id IS NOT NULL)
);
CREATE TRIGGER identity_providers_set_updated_at
    BEFORE UPDATE ON identity.identity_providers
    FOR EACH ROW EXECUTE FUNCTION util.set_updated_at();
SELECT util.enable_tenant_rls('identity.identity_providers');

CREATE TABLE identity.federated_identities (
    tenant_id        uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id               uuid        NOT NULL DEFAULT util.uuid_v7(),
    user_id          uuid        NOT NULL,
    provider_id      uuid        NOT NULL,
    provider_subject text        NOT NULL, -- the IdP's "sub" claim
    email_at_link    extensions.citext,
    last_login_at    timestamptz,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES identity.users (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, provider_id) REFERENCES identity.identity_providers (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT federated_identities_subject_key UNIQUE (tenant_id, provider_id, provider_subject)
);
CREATE INDEX federated_identities_user_idx ON identity.federated_identities (tenant_id, user_id);
SELECT util.enable_tenant_rls('identity.federated_identities');

CREATE TABLE identity.groups (
    tenant_id   uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id          uuid        NOT NULL DEFAULT util.uuid_v7(),
    key         text        NOT NULL,
    name        text        NOT NULL,
    description text,
    source      text        NOT NULL DEFAULT 'local',
    external_id text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT groups_key_key UNIQUE (tenant_id, key),
    CONSTRAINT groups_source_check CHECK (source IN ('local', 'idp'))
);
CREATE TRIGGER groups_set_updated_at
    BEFORE UPDATE ON identity.groups
    FOR EACH ROW EXECUTE FUNCTION util.set_updated_at();
SELECT util.enable_tenant_rls('identity.groups');

CREATE TABLE identity.sessions (
    tenant_id       uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id              uuid        NOT NULL DEFAULT util.uuid_v7(),
    user_id         uuid        NOT NULL,
    application_id  uuid,       -- FK to apps.applications is added by a later migration
    amr             text[]      NOT NULL DEFAULT '{}',
    acr             text,
    ip              inet,
    user_agent      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz NOT NULL DEFAULT now(),
    idle_expires_at timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    revoke_reason   text,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES identity.users (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX sessions_active_user_idx ON identity.sessions (tenant_id, user_id) WHERE revoked_at IS NULL;
-- Full index for the ON DELETE CASCADE from users (and tenant hard-delete), which must also reach
-- revoked sessions; the partial index above cannot serve it.
CREATE INDEX sessions_user_idx ON identity.sessions (tenant_id, user_id);
SELECT util.enable_tenant_rls('identity.sessions');

CREATE TABLE identity.refresh_tokens (
    tenant_id      uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id             uuid        NOT NULL DEFAULT util.uuid_v7(),
    session_id     uuid        NOT NULL,
    application_id uuid,       -- FK to apps.applications is added by a later migration
    token_hash     text        NOT NULL,
    family_id      uuid        NOT NULL, -- whole rotation chain; revoked together on reuse detection
    parent_id      uuid,
    scopes         text[]      NOT NULL DEFAULT '{}',
    issued_at      timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz NOT NULL,
    rotated_at     timestamptz,
    revoked_at     timestamptz,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, session_id) REFERENCES identity.sessions (tenant_id, id) ON DELETE CASCADE,
    -- Purging an old rotated token must not take its descendants with it.
    FOREIGN KEY (tenant_id, parent_id) REFERENCES identity.refresh_tokens (tenant_id, id) ON DELETE SET NULL (parent_id),
    CONSTRAINT refresh_tokens_token_hash_key UNIQUE (token_hash)
);
CREATE INDEX refresh_tokens_session_idx ON identity.refresh_tokens (tenant_id, session_id);
CREATE INDEX refresh_tokens_family_idx ON identity.refresh_tokens (tenant_id, family_id);
CREATE INDEX refresh_tokens_parent_idx ON identity.refresh_tokens (tenant_id, parent_id) WHERE parent_id IS NOT NULL;
SELECT util.enable_tenant_rls('identity.refresh_tokens');

CREATE TABLE identity.one_time_tokens (
    tenant_id   uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id          uuid        NOT NULL DEFAULT util.uuid_v7(),
    user_id     uuid,
    purpose     text        NOT NULL,
    token_hash  text        NOT NULL,
    metadata    jsonb       NOT NULL DEFAULT '{}',
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES identity.users (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT one_time_tokens_purpose_check
        CHECK (purpose IN ('email_verification', 'password_reset', 'invitation', 'magic_link')),
    CONSTRAINT one_time_tokens_token_hash_key UNIQUE (token_hash)
);
CREATE INDEX one_time_tokens_user_idx ON identity.one_time_tokens (tenant_id, user_id) WHERE user_id IS NOT NULL;
SELECT util.enable_tenant_rls('identity.one_time_tokens');
