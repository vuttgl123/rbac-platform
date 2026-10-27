SET LOCAL ROLE rbac_owner;

-- V8: append-only audit log, range-partitioned by month on occurred_at (UTC, see V6).
--
-- tenant_id deliberately has NO foreign key to tenancy.tenants: the log is append-only and bounded by
-- retention (drop old partitions), so it must not block a tenant hard-delete nor pay RI cost per insert.
-- Append-only is enforced by privileges only (no row-level trigger): util.ensure_monthly_partitions must
-- be able to move rows out of the DEFAULT partition when it attaches a late month.
-- metadata must never contain secrets.
CREATE TABLE audit.events (
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL DEFAULT util.uuid_v7(),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor_type  text        NOT NULL,
    actor_id    text,
    action      text        NOT NULL, -- dotted name, e.g. user.login.succeeded
    target_type text,
    target_id   text,
    outcome     text        NOT NULL,
    ip          inet,
    user_agent  text,
    request_id  text,
    metadata    jsonb       NOT NULL DEFAULT '{}',
    PRIMARY KEY (tenant_id, occurred_at, id),
    CONSTRAINT events_actor_type_check CHECK (actor_type IN ('user', 'application', 'operator', 'system')),
    CONSTRAINT events_outcome_check CHECK (outcome IN ('success', 'failure'))
) PARTITION BY RANGE (occurred_at);

CREATE TABLE audit.events_default PARTITION OF audit.events DEFAULT;
SELECT util.ensure_monthly_partitions('audit.events', 3);

CREATE INDEX events_tenant_action_idx ON audit.events (tenant_id, action, occurred_at DESC);

SELECT util.enable_tenant_rls('audit.events');
-- Append-only for both application roles: INSERT and SELECT only. Retention drops whole partitions
-- through the SECURITY DEFINER util.drop_partitions_older_than.
REVOKE UPDATE, DELETE ON audit.events FROM rbac_app, rbac_admin;
