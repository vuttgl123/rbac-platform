SET LOCAL ROLE rbac_owner;

-- V10: the system tenant `platform` and its first operator account (spec 5.1).
-- The operator email comes from the Flyway placeholder platform_operator_email (environment variable
-- PLATFORM_OPERATOR_EMAIL), never hardcoded. It is read exactly once, dollar-quoted so no quote in it
-- can break out of the literal, and validated before anything is written: once applied this file is
-- frozen, so an empty or malformed value must fail the migration instead of seeding a bad account.
-- The account is created 'invited' and has no password and no invitation token; the service issues
-- the invitation later.
-- Operator rights are the `operator` role on the platform tenant's builtin `tenant` object. The relation
-- is builtin (inserted here as rbac_owner), so no tenant role can delete, rename or widen it.
-- Flyway does not substitute a placeholder written directly after a `$`, hence the space after the
-- opening $e$ tag, which substr() removes again.
DO $do$
DECLARE
    v_email       text := substr($e$ ${platform_operator_email}$e$, 2);
    v_tenant_id   uuid;
    v_operator_id uuid;
    v_tenant_type int;
    v_user_type   int;
    v_operator    int;
BEGIN
    IF v_email IS NULL OR v_email !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' THEN
        RAISE EXCEPTION 'invalid_operator_email: placeholder platform_operator_email must be an email address'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT ct.tenant_id, ct.admin_user_id INTO v_tenant_id, v_operator_id
    FROM tenancy.create_tenant('platform', 'Platform', v_email, NULL, NULL, NULL) AS ct;

    SELECT rt.id INTO v_tenant_type
    FROM authz.resource_types rt WHERE rt.tenant_id = v_tenant_id AND rt.key = 'tenant';
    SELECT rt.id INTO v_user_type
    FROM authz.resource_types rt WHERE rt.tenant_id = v_tenant_id AND rt.key = 'user';

    INSERT INTO authz.relations (tenant_id, resource_type_id, key, kind, name, is_builtin)
    VALUES (v_tenant_id, v_tenant_type, 'operator', 'role', 'Operator', true)
    RETURNING id INTO v_operator;

    INSERT INTO authz.relation_subject_types (tenant_id, relation_id, subject_type_id)
    VALUES (v_tenant_id, v_operator, v_user_type);

    -- tenant:<platform>#operator@user:<operator>
    INSERT INTO authz.relationships (tenant_id, object_type_id, object_id, relation_id, subject_type_id, subject_id)
    VALUES (v_tenant_id, v_tenant_type, v_tenant_id::text, v_operator, v_user_type, v_operator_id::text);

    UPDATE tenancy.tenants
    SET settings = settings || '{"mfa":{"required":"required"}}'
    WHERE id = v_tenant_id;
END
$do$;
