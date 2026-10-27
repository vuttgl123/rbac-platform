SET LOCAL ROLE rbac_owner;

-- V9: atomic tenant provisioning (spec 5.1). Only platform operators (DB role rbac_admin) create tenants.
--
-- One call, one transaction: tenant (the insert trigger seeds the builtin model), first admin user
-- (status 'invited'), tuple tenant:<id>#admin@user:<admin>, optional invitation token, audit event.
-- A tenant therefore never exists without an admin. Runs as rbac_owner (SECURITY DEFINER), whose
-- owner_all policies see every tenant, so the caller needs no tenant context.
--
-- The tuple's changelog row takes its actor from app.actor_type / app.actor_id; they are set for the
-- duration of the call and restored afterwards so they do not leak into the caller's transaction.
CREATE FUNCTION tenancy.create_tenant(
    p_slug                  text,
    p_name                  text,
    p_admin_email           text,
    p_invitation_token_hash text,
    p_invitation_expires_at timestamptz,
    p_actor_id              uuid
) RETURNS TABLE (tenant_id uuid, admin_user_id uuid)
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, extensions
AS $$
#variable_conflict use_column
DECLARE
    v_tenant_id    uuid;
    v_admin_id     uuid;
    v_actor_type   text := CASE WHEN p_actor_id IS NULL THEN 'system' ELSE 'operator' END;
    v_prev_type    text := current_setting('app.actor_type', true);
    v_prev_id      text := current_setting('app.actor_id', true);
    v_tenant_type  int;
    v_user_type    int;
    v_admin_rel    int;
BEGIN
    IF p_invitation_token_hash IS NOT NULL AND p_invitation_expires_at IS NULL THEN
        RAISE EXCEPTION 'invitation_expiry_required: an invitation token hash needs an expiry'
            USING ERRCODE = 'check_violation';
    END IF;

    PERFORM set_config('app.actor_type', v_actor_type, true);
    PERFORM set_config('app.actor_id', coalesce(p_actor_id::text, ''), true);

    -- 1. Tenant; the AFTER INSERT trigger seeds the builtin types and relations.
    INSERT INTO tenancy.tenants (slug, name)
    VALUES (p_slug, p_name)
    RETURNING id INTO v_tenant_id;

    -- 2. First admin, invited and without credentials.
    INSERT INTO identity.users (tenant_id, email, status)
    VALUES (v_tenant_id, p_admin_email, 'invited')
    RETURNING id INTO v_admin_id;

    -- 3. tenant:<tenant_id>#admin@user:<admin_id>
    SELECT rt.id INTO v_tenant_type
    FROM authz.resource_types rt
    WHERE rt.tenant_id = v_tenant_id AND rt.key = 'tenant';
    SELECT rt.id INTO v_user_type
    FROM authz.resource_types rt
    WHERE rt.tenant_id = v_tenant_id AND rt.key = 'user';
    SELECT rel.id INTO v_admin_rel
    FROM authz.relations rel
    WHERE rel.tenant_id = v_tenant_id AND rel.resource_type_id = v_tenant_type AND rel.key = 'admin';

    INSERT INTO authz.relationships (tenant_id, object_type_id, object_id, relation_id, subject_type_id, subject_id)
    VALUES (v_tenant_id, v_tenant_type, v_tenant_id::text, v_admin_rel, v_user_type, v_admin_id::text);

    -- 4. Invitation; the service keeps the raw token, the database only the hash.
    IF p_invitation_token_hash IS NOT NULL THEN
        INSERT INTO identity.one_time_tokens (tenant_id, user_id, purpose, token_hash, expires_at)
        VALUES (v_tenant_id, v_admin_id, 'invitation', p_invitation_token_hash, p_invitation_expires_at);
    END IF;

    -- 5. Audit.
    INSERT INTO audit.events (tenant_id, actor_type, actor_id, action, target_type, target_id, outcome, metadata)
    VALUES (v_tenant_id, v_actor_type, p_actor_id::text, 'tenant.created', 'tenant', v_tenant_id::text,
            'success', jsonb_build_object('slug', p_slug, 'admin_user_id', v_admin_id));

    PERFORM set_config('app.actor_type', coalesce(v_prev_type, ''), true);
    PERFORM set_config('app.actor_id', coalesce(v_prev_id, ''), true);

    tenant_id := v_tenant_id;
    admin_user_id := v_admin_id;
    RETURN NEXT;
END
$$;

REVOKE EXECUTE ON FUNCTION tenancy.create_tenant(text, text, text, text, timestamptz, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.create_tenant(text, text, text, text, timestamptz, uuid) TO rbac_admin;
