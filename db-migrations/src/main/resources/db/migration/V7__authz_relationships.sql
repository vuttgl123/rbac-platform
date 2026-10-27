SET LOCAL ROLE rbac_owner;

-- V7: authz tuple store (Zanzibar-style relationships), its validation trigger, the partitioned
-- changelog, and cascade-delete of tuples when a platform-owned entity is deleted.
--
-- Validation and cascade trigger functions run as the INVOKER on purpose: they must see only the
-- caller's tenant through RLS (rbac_app) and need no extra privileges. The changelog trigger is the
-- exception: it is SECURITY DEFINER so that no application role needs (or has) INSERT on the log.

-- authz.relationships ----------------------------------------------------------------------------
-- One row = one fact "object#relation@subject[#subject_relation]", optionally conditional/expiring.
-- object_id / subject_id are polymorphic references: no foreign keys (see validate_relationship).
CREATE TABLE authz.relationships (
    tenant_id           uuid        NOT NULL REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    id                  bigint      GENERATED ALWAYS AS IDENTITY,
    object_type_id      int         NOT NULL,
    object_id           text        NOT NULL,
    relation_id         int         NOT NULL,
    subject_type_id     int         NOT NULL,
    subject_id          text        NOT NULL, -- '*' = wildcard
    subject_relation_id int,                  -- the "#member" in group:x#member
    condition_id        int,
    condition_context   jsonb,                -- pre-bound parameters, e.g. {"max_amount": 50000000}
    expires_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    -- relation must belong to the object type
    FOREIGN KEY (tenant_id, relation_id, object_type_id)
        REFERENCES authz.relations (tenant_id, id, resource_type_id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, subject_type_id)
        REFERENCES authz.resource_types (tenant_id, id) ON DELETE RESTRICT,
    -- subject relation must belong to the subject type; MATCH SIMPLE skips it while NULL
    FOREIGN KEY (tenant_id, subject_relation_id, subject_type_id)
        REFERENCES authz.relations (tenant_id, id, resource_type_id),
    FOREIGN KEY (tenant_id, condition_id)
        REFERENCES authz.conditions (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT relationships_object_id_length CHECK (char_length(object_id) BETWEEN 1 AND 256),
    CONSTRAINT relationships_subject_id_length CHECK (char_length(subject_id) BETWEEN 1 AND 256),
    CONSTRAINT relationships_condition_context_check
        CHECK (condition_context IS NULL OR condition_id IS NOT NULL),
    -- Forward direction too: "who holds relation R on object O".
    CONSTRAINT relationships_tuple_key UNIQUE NULLS NOT DISTINCT
        (tenant_id, object_type_id, object_id, relation_id, subject_type_id, subject_id, subject_relation_id)
);
-- Reverse direction: "which objects can this subject reach", "which groups is this user in".
CREATE INDEX relationships_reverse_idx
    ON authz.relationships (tenant_id, subject_type_id, subject_id, relation_id, object_type_id)
    INCLUDE (object_id, subject_relation_id);
-- Expiry cleanup job (cross-tenant, runs as owner/admin).
CREATE INDEX relationships_expires_at_idx ON authz.relationships (expires_at) WHERE expires_at IS NOT NULL;
-- Support the RESTRICT / NO ACTION foreign keys when a relation or condition is deleted.
CREATE INDEX relationships_relation_idx ON authz.relationships (tenant_id, relation_id);
CREATE INDEX relationships_subject_relation_idx ON authz.relationships (tenant_id, subject_relation_id)
    WHERE subject_relation_id IS NOT NULL;
CREATE INDEX relationships_condition_idx ON authz.relationships (tenant_id, condition_id)
    WHERE condition_id IS NOT NULL;
SELECT util.enable_tenant_rls('authz.relationships');

-- authz.validate_relationship -----------------------------------------------------------------------
-- Second line of defence (the service validates first). Raises check_violation with a token:
--   relation_not_found, relation_is_permission, subject_type_not_allowed, condition_not_allowed,
--   builtin_entity_missing.
-- A relation that is not visible to the caller or does not exist fails closed (relation_not_found).
-- Platform-owned entities (user, group, application, tenant) are referenced by canonical lowercase
-- UUID text; the entity must exist in the tuple's tenant. Wildcard subjects skip the existence check.
CREATE FUNCTION authz.validate_relationship() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, extensions
AS $$
DECLARE
    v_kind            text;
    v_allow_wildcard  boolean;
    v_allow_condition boolean;
    v_side            text;
    v_type_id         int;
    v_entity_id       text;
    v_key             text;
    v_ok              boolean;
BEGIN
    SELECT r.kind INTO v_kind
    FROM authz.relations r
    WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.relation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'relation_not_found: relation % does not exist in this tenant', NEW.relation_id
            USING ERRCODE = 'check_violation';
    END IF;

    IF v_kind = 'permission' THEN
        RAISE EXCEPTION 'relation_is_permission: relation % is a derived permission and cannot hold tuples',
            NEW.relation_id USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.subject_id = '*' AND NEW.subject_relation_id IS NOT NULL THEN
        RAISE EXCEPTION 'subject_type_not_allowed: a wildcard subject cannot carry a subject relation'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT rst.allow_wildcard, rst.allow_condition INTO v_allow_wildcard, v_allow_condition
    FROM authz.relation_subject_types rst
    WHERE rst.tenant_id = NEW.tenant_id
      AND rst.relation_id = NEW.relation_id
      AND rst.subject_type_id = NEW.subject_type_id
      AND rst.subject_relation_id IS NOT DISTINCT FROM NEW.subject_relation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'subject_type_not_allowed: relation % does not allow subject type % (relation %)',
            NEW.relation_id, NEW.subject_type_id, NEW.subject_relation_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.subject_id = '*' AND NOT v_allow_wildcard THEN
        RAISE EXCEPTION 'subject_type_not_allowed: relation % does not allow wildcard subjects of type %',
            NEW.relation_id, NEW.subject_type_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.condition_id IS NOT NULL AND NOT v_allow_condition THEN
        RAISE EXCEPTION 'condition_not_allowed: relation % does not allow conditional subjects of type %',
            NEW.relation_id, NEW.subject_type_id
            USING ERRCODE = 'check_violation';
    END IF;

    -- Existence of platform-owned entities, object side then subject side.
    FOREACH v_side IN ARRAY ARRAY['object', 'subject'] LOOP
        IF v_side = 'object' THEN
            v_type_id := NEW.object_type_id;
            v_entity_id := NEW.object_id;
        ELSE
            v_type_id := NEW.subject_type_id;
            v_entity_id := NEW.subject_id;
            CONTINUE WHEN v_entity_id = '*';
        END IF;

        SELECT rt.key INTO v_key
        FROM authz.resource_types rt
        WHERE rt.tenant_id = NEW.tenant_id
          AND rt.id = v_type_id
          AND rt.is_builtin
          AND rt.key IN ('user', 'group', 'application', 'tenant');
        CONTINUE WHEN v_key IS NULL;

        IF v_key = 'tenant' THEN
            v_ok := v_entity_id = NEW.tenant_id::text;
        ELSIF v_entity_id !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
            v_ok := false; -- not a canonical UUID: never cast it
        ELSIF v_key = 'user' THEN
            SELECT EXISTS (SELECT 1 FROM identity.users e
                           WHERE e.tenant_id = NEW.tenant_id AND e.id = v_entity_id::uuid) INTO v_ok;
        ELSIF v_key = 'group' THEN
            SELECT EXISTS (SELECT 1 FROM identity.groups e
                           WHERE e.tenant_id = NEW.tenant_id AND e.id = v_entity_id::uuid) INTO v_ok;
        ELSE
            SELECT EXISTS (SELECT 1 FROM apps.applications e
                           WHERE e.tenant_id = NEW.tenant_id AND e.id = v_entity_id::uuid) INTO v_ok;
        END IF;

        IF NOT v_ok THEN
            RAISE EXCEPTION 'builtin_entity_missing: % %:% does not exist in this tenant',
                v_side, v_key, left(v_entity_id, 64)
                USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;

    RETURN NEW;
END
$$;

CREATE TRIGGER relationships_validate
    BEFORE INSERT OR UPDATE ON authz.relationships
    FOR EACH ROW EXECUTE FUNCTION authz.validate_relationship();

-- authz.relationship_changes ---------------------------------------------------------------------------
-- Append-only changelog, range-partitioned by month on changed_at (UTC, see V6). seq is allocated in
-- write order, not commit order: readers must combine txid with pg_snapshot_xmin() (spec 7.5).
CREATE TABLE authz.relationship_changes (
    seq                 bigserial,
    changed_at          timestamptz NOT NULL DEFAULT now(),
    tenant_id           uuid        NOT NULL,
    op                  text        NOT NULL,
    relationship_id     bigint      NOT NULL,
    object_type_id      int         NOT NULL,
    object_id           text        NOT NULL,
    relation_id         int         NOT NULL,
    subject_type_id     int         NOT NULL,
    subject_id          text        NOT NULL,
    subject_relation_id int,
    condition_id        int,
    condition_context   jsonb,
    expires_at          timestamptz,
    actor_type          text,
    actor_id            text,
    txid                xid8        NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY (changed_at, seq),
    CONSTRAINT relationship_changes_op_check CHECK (op IN ('insert', 'delete'))
) PARTITION BY RANGE (changed_at);

CREATE TABLE authz.relationship_changes_default PARTITION OF authz.relationship_changes DEFAULT;
SELECT util.ensure_monthly_partitions('authz.relationship_changes', 3);

-- Readers page one tenant's changes by seq.
CREATE INDEX relationship_changes_tenant_seq_idx ON authz.relationship_changes (tenant_id, seq);

SELECT util.enable_tenant_rls('authz.relationship_changes');
-- Read-only for both application roles: rows are written only by the SECURITY DEFINER log trigger
-- (a forged row, e.g. a huge seq, would stall every cache invalidator), never updated or deleted;
-- retention drops whole partitions (util.drop_partitions_older_than). No grant on the seq sequence.
REVOKE INSERT, UPDATE, DELETE ON authz.relationship_changes FROM rbac_app, rbac_admin;

-- authz.log_relationship_change ---------------------------------------------------------------------------
-- UPDATE is logged as delete (old values) followed by insert (new values).
-- Actor comes from the transaction-local settings app.actor_type / app.actor_id (NULL if unset/empty).
-- SECURITY DEFINER (rbac_owner): safe because it is an AFTER trigger and OLD/NEW already passed RLS.
CREATE FUNCTION authz.log_relationship_change() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, extensions
AS $$
DECLARE
    v_actor_type text := nullif(current_setting('app.actor_type', true), '');
    v_actor_id   text := nullif(current_setting('app.actor_id', true), '');
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        INSERT INTO authz.relationship_changes (
            tenant_id, op, relationship_id, object_type_id, object_id, relation_id, subject_type_id,
            subject_id, subject_relation_id, condition_id, condition_context, expires_at,
            actor_type, actor_id)
        VALUES (
            OLD.tenant_id, 'delete', OLD.id, OLD.object_type_id, OLD.object_id, OLD.relation_id,
            OLD.subject_type_id, OLD.subject_id, OLD.subject_relation_id, OLD.condition_id,
            OLD.condition_context, OLD.expires_at, v_actor_type, v_actor_id);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        INSERT INTO authz.relationship_changes (
            tenant_id, op, relationship_id, object_type_id, object_id, relation_id, subject_type_id,
            subject_id, subject_relation_id, condition_id, condition_context, expires_at,
            actor_type, actor_id)
        VALUES (
            NEW.tenant_id, 'insert', NEW.id, NEW.object_type_id, NEW.object_id, NEW.relation_id,
            NEW.subject_type_id, NEW.subject_id, NEW.subject_relation_id, NEW.condition_id,
            NEW.condition_context, NEW.expires_at, v_actor_type, v_actor_id);
    END IF;
    RETURN NULL;
END
$$;

REVOKE EXECUTE ON FUNCTION authz.log_relationship_change() FROM PUBLIC;

CREATE TRIGGER relationships_log_change
    AFTER INSERT OR UPDATE OR DELETE ON authz.relationships
    FOR EACH ROW EXECUTE FUNCTION authz.log_relationship_change();

-- authz.delete_entity_relationships --------------------------------------------------------------------------
-- No foreign key can cover polymorphic ids, so deleting a platform-owned entity removes every tuple in
-- its tenant that has it as subject OR object. TG_ARGV[0] is the builtin type key. Plain deletes, no
-- recursion: group cycles (a#member@b#member, b#member@a#member) are removed without trouble.
CREATE FUNCTION authz.delete_entity_relationships() RETURNS trigger
    LANGUAGE plpgsql
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

CREATE TRIGGER users_delete_relationships
    AFTER DELETE ON identity.users
    FOR EACH ROW EXECUTE FUNCTION authz.delete_entity_relationships('user');
CREATE TRIGGER groups_delete_relationships
    AFTER DELETE ON identity.groups
    FOR EACH ROW EXECUTE FUNCTION authz.delete_entity_relationships('group');
CREATE TRIGGER applications_delete_relationships
    AFTER DELETE ON apps.applications
    FOR EACH ROW EXECUTE FUNCTION authz.delete_entity_relationships('application');
