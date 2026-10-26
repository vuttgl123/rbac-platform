SET LOCAL ROLE rbac_owner;

-- V5: authz model. The tenant-defined authorization model: resource types, relations
-- (roles, relationships, permissions), who may hold a relation, how a permission is
-- derived (allow/deny rules), and CEL conditions. Small metadata tables, so ids are
-- int identities. Every change bumps tenancy.tenants.authz_model_revision.
-- The tuple store (authz.relationships) comes later.

CREATE TABLE authz.resource_types (
    tenant_id      uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id             int         GENERATED ALWAYS AS IDENTITY,
    key            text        NOT NULL, -- e.g. document, or crm/document
    name           text        NOT NULL,
    description    text,
    application_id uuid,       -- owning application, for organisation only
    is_builtin     boolean     NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT resource_types_key_key UNIQUE (tenant_id, key),
    FOREIGN KEY (tenant_id, application_id) REFERENCES apps.applications (tenant_id, id)
        ON DELETE SET NULL (application_id)
);
CREATE INDEX resource_types_application_idx ON authz.resource_types (tenant_id, application_id)
    WHERE application_id IS NOT NULL;
CREATE TRIGGER resource_types_set_updated_at
    BEFORE UPDATE ON authz.resource_types
    FOR EACH ROW EXECUTE FUNCTION util.set_updated_at();
SELECT util.enable_tenant_rls('authz.resource_types');

-- One namespace for relations and permissions per resource type, as in SpiceDB.
CREATE TABLE authz.relations (
    tenant_id        uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id               int         GENERATED ALWAYS AS IDENTITY,
    resource_type_id int         NOT NULL,
    key              text        NOT NULL,
    kind             text        NOT NULL,
    name             text        NOT NULL,
    description      text,
    is_builtin       boolean     NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, resource_type_id) REFERENCES authz.resource_types (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT relations_key_key UNIQUE (tenant_id, resource_type_id, key),
    -- Targets of the composite foreign keys below and in the tuple store.
    CONSTRAINT relations_type_key UNIQUE (tenant_id, id, resource_type_id),
    CONSTRAINT relations_kind_key UNIQUE (tenant_id, id, resource_type_id, kind),
    CONSTRAINT relations_kind_check CHECK (kind IN ('role', 'relationship', 'permission'))
);
CREATE TRIGGER relations_set_updated_at
    BEFORE UPDATE ON authz.relations
    FOR EACH ROW EXECUTE FUNCTION util.set_updated_at();
SELECT util.enable_tenant_rls('authz.relations');

-- Who may hold a relation, e.g. folder.editor allows user and group#member.
CREATE TABLE authz.relation_subject_types (
    tenant_id           uuid    NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id                  int     GENERATED ALWAYS AS IDENTITY,
    relation_id         int     NOT NULL,
    subject_type_id     int     NOT NULL,
    subject_relation_id int,    -- the "#member" part of group#member
    allow_wildcard      boolean NOT NULL DEFAULT false,
    allow_condition     boolean NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, relation_id) REFERENCES authz.relations (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, subject_type_id) REFERENCES authz.resource_types (tenant_id, id) ON DELETE RESTRICT,
    -- The subject relation must exist on the subject type. Skipped while subject_relation_id is NULL.
    -- No explicit action (NO ACTION) so a relation may list itself, e.g. group.member allows group#member.
    FOREIGN KEY (tenant_id, subject_relation_id, subject_type_id)
        REFERENCES authz.relations (tenant_id, id, resource_type_id),
    CONSTRAINT relation_subject_types_key
        UNIQUE NULLS NOT DISTINCT (tenant_id, relation_id, subject_type_id, subject_relation_id)
);
CREATE INDEX relation_subject_types_subject_type_idx ON authz.relation_subject_types (tenant_id, subject_type_id);
CREATE INDEX relation_subject_types_subject_relation_idx
    ON authz.relation_subject_types (tenant_id, subject_relation_id) WHERE subject_relation_id IS NOT NULL;
SELECT util.enable_tenant_rls('authz.relation_subject_types');

-- CEL expressions. The database only stores them; the service compiles and evaluates.
CREATE TABLE authz.conditions (
    tenant_id   uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id          int         GENERATED ALWAYS AS IDENTITY,
    key         text        NOT NULL,
    expression  text        NOT NULL,
    parameters  jsonb       NOT NULL DEFAULT '{}', -- parameter name -> CEL type
    description text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT conditions_key_key UNIQUE (tenant_id, key)
);
CREATE TRIGGER conditions_set_updated_at
    BEFORE UPDATE ON authz.conditions
    FOR EACH ROW EXECUTE FUNCTION util.set_updated_at();
SELECT util.enable_tenant_rls('authz.conditions');

-- How a permission is derived. Permission P = union of its allow rules minus union of
-- its deny rules (deny wins). resource_type_id and permission_kind are redundant columns
-- that let composite foreign keys prove the target is a permission of the same type.
CREATE TABLE authz.permission_rules (
    tenant_id        uuid NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id               int  GENERATED ALWAYS AS IDENTITY,
    resource_type_id int  NOT NULL,
    permission_id    int  NOT NULL,
    permission_kind  text NOT NULL DEFAULT 'permission',
    kind             text NOT NULL,
    effect           text NOT NULL DEFAULT 'allow',
    target_key       text NOT NULL, -- text: for 'via' the target may live on several resource types
    via_relation_id  int,
    condition_id     int,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT permission_rules_permission_kind_check CHECK (permission_kind = 'permission'),
    CONSTRAINT permission_rules_kind_check CHECK (kind IN ('computed', 'via', 'tenant')),
    CONSTRAINT permission_rules_effect_check CHECK (effect IN ('allow', 'deny')),
    CONSTRAINT permission_rules_via_check CHECK ((kind = 'via') = (via_relation_id IS NOT NULL)),
    FOREIGN KEY (tenant_id, permission_id, resource_type_id, permission_kind)
        REFERENCES authz.relations (tenant_id, id, resource_type_id, kind) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, via_relation_id, resource_type_id)
        REFERENCES authz.relations (tenant_id, id, resource_type_id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, condition_id) REFERENCES authz.conditions (tenant_id, id) ON DELETE RESTRICT
);
CREATE INDEX permission_rules_permission_idx ON authz.permission_rules (tenant_id, permission_id);
CREATE INDEX permission_rules_via_relation_idx ON authz.permission_rules (tenant_id, via_relation_id)
    WHERE via_relation_id IS NOT NULL;
CREATE INDEX permission_rules_condition_idx ON authz.permission_rules (tenant_id, condition_id)
    WHERE condition_id IS NOT NULL;
SELECT util.enable_tenant_rls('authz.permission_rules');

-- Model revision ---------------------------------------------------------------------
-- SECURITY DEFINER: rbac_app has no UPDATE privilege on tenants.authz_model_revision (V2). Safe
-- because this is an AFTER trigger: NEW/OLD.tenant_id already passed the model table's RLS.
CREATE FUNCTION authz.bump_model_revision() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, extensions
AS $$
DECLARE
    v_tenant_id uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_tenant_id := OLD.tenant_id;
    ELSE
        v_tenant_id := NEW.tenant_id;
    END IF;
    UPDATE tenancy.tenants
    SET authz_model_revision = authz_model_revision + 1
    WHERE id = v_tenant_id;
    RETURN NULL;
END
$$;

REVOKE EXECUTE ON FUNCTION authz.bump_model_revision() FROM PUBLIC;

CREATE TRIGGER resource_types_bump_revision
    AFTER INSERT OR UPDATE OR DELETE ON authz.resource_types
    FOR EACH ROW EXECUTE FUNCTION authz.bump_model_revision();
CREATE TRIGGER relations_bump_revision
    AFTER INSERT OR UPDATE OR DELETE ON authz.relations
    FOR EACH ROW EXECUTE FUNCTION authz.bump_model_revision();
CREATE TRIGGER relation_subject_types_bump_revision
    AFTER INSERT OR UPDATE OR DELETE ON authz.relation_subject_types
    FOR EACH ROW EXECUTE FUNCTION authz.bump_model_revision();
CREATE TRIGGER permission_rules_bump_revision
    AFTER INSERT OR UPDATE OR DELETE ON authz.permission_rules
    FOR EACH ROW EXECUTE FUNCTION authz.bump_model_revision();
CREATE TRIGGER conditions_bump_revision
    AFTER INSERT OR UPDATE OR DELETE ON authz.conditions
    FOR EACH ROW EXECUTE FUNCTION authz.bump_model_revision();

-- Builtin protection -------------------------------------------------------------------
-- Builtin rows cannot be deleted, renamed (key), or demoted (is_builtin true -> false), and only
-- rbac_owner (the seed and V10, which run as rbac_owner) may create a builtin row or promote one
-- (is_builtin false -> true): otherwise a tenant could mint rows the platform trusts as builtin.
CREATE FUNCTION authz.protect_builtin() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, extensions
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.is_builtin THEN
            RAISE EXCEPTION 'builtin_immutable: cannot delete builtin % "%"', TG_TABLE_NAME, OLD.key
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.is_builtin AND current_user <> 'rbac_owner' THEN
            RAISE EXCEPTION 'builtin_immutable: only the platform may create builtin % "%"', TG_TABLE_NAME, NEW.key
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.is_builtin AND NEW.key IS DISTINCT FROM OLD.key THEN
        RAISE EXCEPTION 'builtin_immutable: cannot rename builtin % "%"', TG_TABLE_NAME, OLD.key
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.is_builtin AND NOT NEW.is_builtin THEN
        RAISE EXCEPTION 'builtin_immutable: cannot demote builtin % "%"', TG_TABLE_NAME, OLD.key
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT OLD.is_builtin AND NEW.is_builtin AND current_user <> 'rbac_owner' THEN
        RAISE EXCEPTION 'builtin_immutable: only the platform may promote % "%" to builtin', TG_TABLE_NAME, OLD.key
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER resource_types_protect_builtin_insert
    BEFORE INSERT ON authz.resource_types
    FOR EACH ROW WHEN (NEW.is_builtin) EXECUTE FUNCTION authz.protect_builtin();
CREATE TRIGGER resource_types_protect_builtin_delete
    BEFORE DELETE ON authz.resource_types
    FOR EACH ROW EXECUTE FUNCTION authz.protect_builtin();
CREATE TRIGGER resource_types_protect_builtin_update
    BEFORE UPDATE OF key, is_builtin ON authz.resource_types
    FOR EACH ROW EXECUTE FUNCTION authz.protect_builtin();
CREATE TRIGGER relations_protect_builtin_insert
    BEFORE INSERT ON authz.relations
    FOR EACH ROW WHEN (NEW.is_builtin) EXECUTE FUNCTION authz.protect_builtin();
CREATE TRIGGER relations_protect_builtin_delete
    BEFORE DELETE ON authz.relations
    FOR EACH ROW EXECUTE FUNCTION authz.protect_builtin();
CREATE TRIGGER relations_protect_builtin_update
    BEFORE UPDATE OF key, is_builtin ON authz.relations
    FOR EACH ROW EXECUTE FUNCTION authz.protect_builtin();

-- A relation's kind and resource type are fixed at creation, for every relation: reshaping one in
-- place (e.g. a role into a permission) would silently reinterpret every tuple and rule using it.
-- To change the shape, delete the relation and create a new one.
CREATE FUNCTION authz.protect_relation_shape() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, extensions
AS $$
BEGIN
    IF NEW.kind IS DISTINCT FROM OLD.kind OR NEW.resource_type_id IS DISTINCT FROM OLD.resource_type_id THEN
        RAISE EXCEPTION 'relation_shape_immutable: kind and resource type of relation "%" cannot change', OLD.key
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER relations_protect_shape
    BEFORE UPDATE OF kind, resource_type_id ON authz.relations
    FOR EACH ROW EXECUTE FUNCTION authz.protect_relation_shape();

-- Who may hold a builtin relation (e.g. tenant.admin) is part of the platform model: only rbac_owner
-- may insert, change or delete those rows. Checks the old and the new relation of the row.
-- Invoker on purpose: under rbac_app the lookup sees only the caller's tenant, and a row of another
-- tenant is rejected by RLS anyway.
CREATE FUNCTION authz.protect_builtin_subject_types() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, extensions
AS $$
BEGIN
    IF current_user = 'rbac_owner' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF (TG_OP IN ('UPDATE', 'DELETE') AND EXISTS (
            SELECT 1 FROM authz.relations r
            WHERE r.tenant_id = OLD.tenant_id AND r.id = OLD.relation_id AND r.is_builtin))
       OR (TG_OP IN ('INSERT', 'UPDATE') AND EXISTS (
            SELECT 1 FROM authz.relations r
            WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.relation_id AND r.is_builtin)) THEN
        RAISE EXCEPTION 'builtin_immutable: allowed subject types of a builtin relation cannot change'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;

CREATE TRIGGER relation_subject_types_protect_builtin
    BEFORE INSERT OR UPDATE OR DELETE ON authz.relation_subject_types
    FOR EACH ROW EXECUTE FUNCTION authz.protect_builtin_subject_types();

-- Builtin seeding ------------------------------------------------------------------------
-- tenant (roles admin, member), user, group (relationship member), application.
-- SECURITY DEFINER so a tenant created by any role gets its model; not executable by
-- rbac_app or PUBLIC, only reached through the tenants trigger below.
CREATE FUNCTION authz.seed_builtin_model(p_tenant_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, extensions
AS $$
DECLARE
    v_tenant       int;
    v_user         int;
    v_group        int;
    v_application  int;
    v_admin        int;
    v_member       int;
    v_group_member int;
BEGIN
    INSERT INTO authz.resource_types (tenant_id, key, name, is_builtin)
    VALUES (p_tenant_id, 'tenant', 'Tenant', true) RETURNING id INTO v_tenant;
    INSERT INTO authz.resource_types (tenant_id, key, name, is_builtin)
    VALUES (p_tenant_id, 'user', 'User', true) RETURNING id INTO v_user;
    INSERT INTO authz.resource_types (tenant_id, key, name, is_builtin)
    VALUES (p_tenant_id, 'group', 'Group', true) RETURNING id INTO v_group;
    INSERT INTO authz.resource_types (tenant_id, key, name, is_builtin)
    VALUES (p_tenant_id, 'application', 'Application', true) RETURNING id INTO v_application;

    INSERT INTO authz.relations (tenant_id, resource_type_id, key, kind, name, is_builtin)
    VALUES (p_tenant_id, v_tenant, 'admin', 'role', 'Admin', true) RETURNING id INTO v_admin;
    INSERT INTO authz.relations (tenant_id, resource_type_id, key, kind, name, is_builtin)
    VALUES (p_tenant_id, v_tenant, 'member', 'role', 'Member', true) RETURNING id INTO v_member;
    INSERT INTO authz.relations (tenant_id, resource_type_id, key, kind, name, is_builtin)
    VALUES (p_tenant_id, v_group, 'member', 'relationship', 'Member', true) RETURNING id INTO v_group_member;

    -- group.member: user, group#member (nested groups)
    INSERT INTO authz.relation_subject_types (tenant_id, relation_id, subject_type_id, subject_relation_id)
    VALUES (p_tenant_id, v_group_member, v_user, NULL),
           (p_tenant_id, v_group_member, v_group, v_group_member);

    -- tenant.admin and tenant.member: user, group#member, application
    INSERT INTO authz.relation_subject_types (tenant_id, relation_id, subject_type_id, subject_relation_id)
    SELECT p_tenant_id, r.id, s.type_id, s.relation_id
    FROM (VALUES (v_admin), (v_member)) AS r (id)
    CROSS JOIN (VALUES (v_user, NULL::int),
                       (v_group, v_group_member),
                       (v_application, NULL::int)) AS s (type_id, relation_id);
END
$$;

CREATE FUNCTION authz.on_tenant_created() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, extensions
AS $$
BEGIN
    PERFORM authz.seed_builtin_model(NEW.id);
    RETURN NULL;
END
$$;

REVOKE EXECUTE ON FUNCTION authz.seed_builtin_model(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION authz.on_tenant_created() FROM PUBLIC;

CREATE TRIGGER tenants_seed_builtin_model
    AFTER INSERT ON tenancy.tenants
    FOR EACH ROW EXECUTE FUNCTION authz.on_tenant_created();

-- Tenants created before this migration (none in practice) get their model too.
SELECT authz.seed_builtin_model(t.id) FROM tenancy.tenants t;
