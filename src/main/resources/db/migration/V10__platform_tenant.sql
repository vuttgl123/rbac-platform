SET LOCAL ROLE rbac_owner;

-- V10: the system tenant `platform` and its first operator account (spec 5.1).
-- The operator email comes from the Flyway placeholder platform_operator_email (environment variable
-- PLATFORM_OPERATOR_EMAIL), never hardcoded. The account is created 'invited' and has no password and
-- no invitation token; the service issues the invitation later.
-- Operator rights are the `operator` role on the platform tenant's builtin `tenant` object.
SELECT tenancy.create_tenant('platform', 'Platform', '${platform_operator_email}', NULL, NULL, NULL);

INSERT INTO authz.relations (tenant_id, resource_type_id, key, kind, name, is_builtin)
SELECT t.id, rt.id, 'operator', 'role', 'Operator', false
FROM tenancy.tenants t
JOIN authz.resource_types rt ON rt.tenant_id = t.id AND rt.key = 'tenant'
WHERE t.slug = 'platform';

INSERT INTO authz.relation_subject_types (tenant_id, relation_id, subject_type_id)
SELECT rel.tenant_id, rel.id, ut.id
FROM tenancy.tenants t
JOIN authz.resource_types tt ON tt.tenant_id = t.id AND tt.key = 'tenant'
JOIN authz.relations rel ON rel.tenant_id = t.id AND rel.resource_type_id = tt.id AND rel.key = 'operator'
JOIN authz.resource_types ut ON ut.tenant_id = t.id AND ut.key = 'user'
WHERE t.slug = 'platform';

-- tenant:<platform>#operator@user:<operator>
INSERT INTO authz.relationships (tenant_id, object_type_id, object_id, relation_id, subject_type_id, subject_id)
SELECT t.id, tt.id, t.id::text, rel.id, ut.id, u.id::text
FROM tenancy.tenants t
JOIN authz.resource_types tt ON tt.tenant_id = t.id AND tt.key = 'tenant'
JOIN authz.relations rel ON rel.tenant_id = t.id AND rel.resource_type_id = tt.id AND rel.key = 'operator'
JOIN authz.resource_types ut ON ut.tenant_id = t.id AND ut.key = 'user'
JOIN identity.users u ON u.tenant_id = t.id AND u.email = '${platform_operator_email}'
WHERE t.slug = 'platform';

UPDATE tenancy.tenants
SET settings = settings || '{"mfa":{"required":"required"}}'
WHERE slug = 'platform';
