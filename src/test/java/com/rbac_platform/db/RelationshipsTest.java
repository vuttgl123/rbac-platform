package com.rbac_platform.db;

import static com.rbac_platform.db.Sql.count;
import static com.rbac_platform.db.Sql.errorMessage;
import static com.rbac_platform.db.Sql.exec;
import static com.rbac_platform.db.Sql.one;
import static com.rbac_platform.db.Sql.sqlState;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelationshipsTest {

    private final MigratedDatabase db = MigratedDatabase.get();

    /** Ids of the example model of spec 7.4 plus the builtin pieces the tests need. */
    private record Model(
            int userType, int groupType, int applicationType, int tenantType,
            int groupMember, int tenantAdmin, int tenantMember, int tenantAccountant,
            int folderType, int folderEditor, int folderEdit,
            int documentType, int docParent, int docEditor, int docBlocked, int docEdit, int docView,
            int underLimit) {}

    private static int type(Connection c, UUID t, String key) {
        return one(c, Integer.class, "select id from authz.resource_types where tenant_id = ? and key = ?", t, key);
    }

    private static int relation(Connection c, UUID t, String typeKey, String key) {
        return one(c, Integer.class,
                "select r.id from authz.relations r join authz.resource_types rt "
                + "on rt.tenant_id = r.tenant_id and rt.id = r.resource_type_id "
                + "where r.tenant_id = ? and rt.key = ? and r.key = ?", t, typeKey, key);
    }

    private static int newType(Connection c, UUID t, String key) {
        return one(c, Integer.class,
                "insert into authz.resource_types (tenant_id, key, name) values (?, ?, ?) returning id", t, key, key);
    }

    private static int newRelation(Connection c, UUID t, int typeId, String key, String kind) {
        return one(c, Integer.class,
                "insert into authz.relations (tenant_id, resource_type_id, key, name, kind) values (?, ?, ?, ?, ?) "
                + "returning id", t, typeId, key, key, kind);
    }

    private static void allow(Connection c, UUID t, int relationId, int subjectTypeId, Integer subjectRelationId,
                              boolean wildcard, boolean condition) {
        exec(c, "insert into authz.relation_subject_types (tenant_id, relation_id, subject_type_id, "
                + "subject_relation_id, allow_wildcard, allow_condition) values (?, ?, ?, ?, ?, ?)",
                t, relationId, subjectTypeId, subjectRelationId, wildcard, condition);
    }

    private static void rule(Connection c, UUID t, int typeId, int permissionId, String kind, String effect,
                             String target, Integer via) {
        exec(c, "insert into authz.permission_rules (tenant_id, resource_type_id, permission_id, kind, effect, "
                + "target_key, via_relation_id) values (?, ?, ?, ?, ?, ?, ?)",
                t, typeId, permissionId, kind, effect, target, via);
    }

    /** Builds the spec 7.4 model (folder, document, tenant.accountant with a condition) for one tenant. */
    private Model canonicalModel(UUID t) {
        return db.inTx(null, null, c -> {
            int user = type(c, t, "user");
            int group = type(c, t, "group");
            int application = type(c, t, "application");
            int tenant = type(c, t, "tenant");
            int groupMember = relation(c, t, "group", "member");

            int folder = newType(c, t, "folder");
            int folderEditor = newRelation(c, t, folder, "editor", "role");
            allow(c, t, folderEditor, user, null, false, false);
            allow(c, t, folderEditor, group, groupMember, false, false);
            int folderEdit = newRelation(c, t, folder, "edit", "permission");
            rule(c, t, folder, folderEdit, "computed", "allow", "editor", null);

            int document = newType(c, t, "document");
            int parent = newRelation(c, t, document, "parent", "relationship");
            allow(c, t, parent, folder, null, false, false);
            int docEditor = newRelation(c, t, document, "editor", "role");
            allow(c, t, docEditor, user, null, false, false);
            allow(c, t, docEditor, group, groupMember, false, false);
            int blocked = newRelation(c, t, document, "blocked", "role");
            allow(c, t, blocked, user, null, false, false);
            allow(c, t, blocked, group, groupMember, false, false);
            int edit = newRelation(c, t, document, "edit", "permission");
            int view = newRelation(c, t, document, "view", "permission");
            rule(c, t, document, edit, "computed", "allow", "editor", null);
            rule(c, t, document, edit, "via", "allow", "edit", parent);
            rule(c, t, document, edit, "computed", "deny", "blocked", null);
            rule(c, t, document, view, "computed", "allow", "edit", null);

            // spec 7.4 conditional example: tenant:<id>#accountant@user:<u> if under_limit
            int accountant = newRelation(c, t, tenant, "accountant", "role");
            allow(c, t, accountant, user, null, false, true);
            int underLimit = one(c, Integer.class,
                    "insert into authz.conditions (tenant_id, key, expression, parameters) "
                    + "values (?, 'under_limit', 'amount < max_amount', "
                    + "'{\"amount\":\"double\",\"max_amount\":\"double\"}'::jsonb) returning id", t);

            return new Model(user, group, application, tenant, groupMember, relation(c, t, "tenant", "admin"),
                    relation(c, t, "tenant", "member"), accountant, folder, folderEditor, folderEdit, document,
                    parent, docEditor, blocked, edit, view, underLimit);
        });
    }

    private UUID newUser(UUID t) {
        return db.insertUser(t, "u-" + UUID.randomUUID() + "@example.com");
    }

    private UUID newGroup(UUID t) {
        return db.inTx(null, null, c -> one(c, UUID.class,
                "insert into identity.groups (tenant_id, key, name) values (?, ?, 'g') returning id",
                t, "g-" + UUID.randomUUID()));
    }

    private UUID newApplication(UUID t) {
        return db.inTx(null, null, c -> one(c, UUID.class,
                "insert into apps.applications (tenant_id, client_id, name, type, token_endpoint_auth_method) "
                + "values (?, ?, 'app', 'm2m', 'client_secret_basic') returning id",
                t, "cid-" + UUID.randomUUID()));
    }

    /** Inserts a tuple on an open connection and returns its id. */
    private static long put(Connection c, UUID t, int objectType, String objectId, int relation, int subjectType,
                            String subjectId, Integer subjectRelation) {
        return put(c, t, objectType, objectId, relation, subjectType, subjectId, subjectRelation, null, null);
    }

    private static long put(Connection c, UUID t, int objectType, String objectId, int relation, int subjectType,
                            String subjectId, Integer subjectRelation, Integer condition, String context) {
        return one(c, Long.class,
                "insert into authz.relationships (tenant_id, object_type_id, object_id, relation_id, "
                + "subject_type_id, subject_id, subject_relation_id, condition_id, condition_context) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) returning id",
                t, objectType, objectId, relation, subjectType, subjectId, subjectRelation, condition, context);
    }

    private static String msg(Sql.ThrowingRunnable r) {
        return errorMessage(r);
    }

    private static long changes(Connection c, UUID t, String op) {
        return count(c, "select count(*) from authz.relationship_changes where tenant_id = ? and op = ?", t, op);
    }

    @Test
    void storesCanonicalExampleAndLogsInserts() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID an = newUser(t);
        UUID g = newGroup(t);
        db.inTx("rbac_app", t, c -> {
            put(c, t, m.groupType, g.toString(), m.groupMember, m.userType, an.toString(), null);
            put(c, t, m.folderType, "ke-toan", m.folderEditor, m.groupType, g.toString(), m.groupMember);
            put(c, t, m.documentType, "123", m.docParent, m.folderType, "ke-toan", null);
            assertThat(count(c, "select count(*) from authz.relationships where tenant_id = ?", t)).isEqualTo(3);
            assertThat(changes(c, t, "insert")).isEqualTo(3);
            assertThat(changes(c, t, "delete")).isZero();
            return null;
        });
    }

    @Test
    void rejectsPermissionRelation() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        assertThat(msg(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.documentType, "123", m.docEdit, m.userType, u.toString(), null))))
                .contains("relation_is_permission");
    }

    @Test
    void rejectsDisallowedSubjectType() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        // document.parent only allows folder
        assertThat(msg(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.documentType, "123", m.docParent, m.userType, u.toString(), null))))
                .contains("subject_type_not_allowed");
        // folder.editor allows user and group#member, but not a bare group
        UUID g = newGroup(t);
        assertThat(msg(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.folderType, "f", m.folderEditor, m.groupType, g.toString(), null))))
                .contains("subject_type_not_allowed");
    }

    @Test
    void rejectsWildcardWhenNotAllowed() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        assertThat(msg(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.folderType, "f", m.folderEditor, m.userType, "*", null))))
                .contains("subject_type_not_allowed");
    }

    @Test
    void acceptsWildcardWhenAllowedButNotWithSubjectRelation() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        // allow wildcard users on document.editor, and (wrongly configured) wildcard group#member too
        db.inTx(null, null, c -> {
            exec(c, "update authz.relation_subject_types set allow_wildcard = true "
                    + "where tenant_id = ? and relation_id = ? and subject_type_id = ? and subject_relation_id is null",
                    t, m.docEditor, m.userType);
            exec(c, "update authz.relation_subject_types set allow_wildcard = true "
                    + "where tenant_id = ? and relation_id = ? and subject_type_id = ? and subject_relation_id is not null",
                    t, m.docEditor, m.groupType);
            return null;
        });
        db.inTx("rbac_app", t, c ->
                put(c, t, m.documentType, "pub", m.docEditor, m.userType, "*", null)); // '*' skips the existence check
        assertThat(msg(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.documentType, "pub2", m.docEditor, m.groupType, "*", m.groupMember))))
                .contains("subject_type_not_allowed");
    }

    @Test
    void rejectsConditionWhenNotAllowed() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        assertThat(msg(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.folderType, "f", m.folderEditor, m.userType, u.toString(), null,
                        m.underLimit, "{\"max_amount\": 1}"))))
                .contains("condition_not_allowed");
    }

    @Test
    void acceptsConditionWithContext() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID binh = newUser(t);
        db.inTx("rbac_app", t, c -> {
            put(c, t, m.tenantType, t.toString(), m.tenantAccountant, m.userType, binh.toString(), null,
                    m.underLimit, "{\"max_amount\": 50000000}");
            assertThat(one(c, String.class,
                    "select condition_context->>'max_amount' from authz.relationships where tenant_id = ?", t))
                    .isEqualTo("50000000");
            return null;
        });
    }

    @Test
    void conditionContextWithoutConditionRejected() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        assertThat(sqlState(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.folderType, "f", m.folderEditor, m.userType, u.toString(), null, null, "{\"a\": 1}"))))
                .isEqualTo("23514");
    }

    @Test
    void rejectsRelationOfAnotherObjectType() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        // object type document, but relation folder.editor
        assertThat(sqlState(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.documentType, "123", m.folderEditor, m.userType, u.toString(), null))))
                .isEqualTo("23503");
    }

    @Test
    void rejectsMissingOrMalformedBuiltinIds() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID g = newGroup(t);
        UUID u = newUser(t);
        String randomUuid = UUID.randomUUID().toString();
        assertThat(msg(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.groupType, g.toString(), m.groupMember, m.userType, randomUuid, null))))
                .contains("builtin_entity_missing");
        assertThat(msg(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.groupType, g.toString(), m.groupMember, m.userType, "not-a-uuid", null))))
                .contains("builtin_entity_missing");
        // missing object side (group that does not exist)
        assertThat(msg(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.groupType, randomUuid, m.groupMember, m.userType, u.toString(), null))))
                .contains("builtin_entity_missing");
        // a non-canonical spelling of a real id is not accepted either (one tuple <-> one text form)
        assertThat(msg(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.groupType, g.toString(), m.groupMember, m.userType, u.toString().toUpperCase(), null))))
                .contains("builtin_entity_missing");
    }

    @Test
    void rejectsForeignTenantEntitiesAndTenantObjects() {
        UUID t = db.insertTenant();
        UUID other = db.insertTenant();
        Model m = canonicalModel(t);
        UUID mine = newUser(t);
        UUID theirs = newUser(other);
        // tenant:<other tenant>#admin@user:<mine>
        assertThat(msg(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.tenantType, other.toString(), m.tenantAdmin, m.userType, mine.toString(), null))))
                .contains("builtin_entity_missing");
        // own tenant object is fine
        db.inTx("rbac_app", t, c ->
                put(c, t, m.tenantType, t.toString(), m.tenantAdmin, m.userType, mine.toString(), null));
        // a user of another tenant as subject
        assertThat(msg(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.tenantType, t.toString(), m.tenantMember, m.userType, theirs.toString(), null))))
                .contains("builtin_entity_missing");
        // superuser path too: the trigger scopes the lookup by the tuple's tenant, not by the caller
        assertThat(msg(() -> db.inTx(null, null, c ->
                put(c, t, m.tenantType, t.toString(), m.tenantMember, m.userType, theirs.toString(), null))))
                .contains("builtin_entity_missing");
    }

    @Test
    void applicationSubjectsAreValidated() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID app = newApplication(t);
        db.inTx("rbac_app", t, c ->
                put(c, t, m.tenantType, t.toString(), m.tenantMember, m.applicationType, app.toString(), null));
        assertThat(msg(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.tenantType, t.toString(), m.tenantAdmin, m.applicationType,
                        UUID.randomUUID().toString(), null))))
                .contains("builtin_entity_missing");
    }

    @Test
    void appCannotStoreTuplesForAnotherTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        Model mb = canonicalModel(b);
        UUID ub = newUser(b);
        // The relation is invisible under a's context: validate_relationship rejects it (relation_not_found,
        // 23514) before RLS WITH CHECK would (42501). Either way the write is refused.
        assertThat(sqlState(() -> db.inTx("rbac_app", a, c ->
                put(c, b, mb.folderType, "f", mb.folderEditor, mb.userType, ub.toString(), null))))
                .isIn("23514", "42501");
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from authz.relationships where tenant_id = ?", b)).isZero();
            return null;
        });
    }

    @Test
    void rejectsUnknownRelationInsteadOfSkippingValidation() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        // Superuser bypasses RLS; the relation simply does not exist. Validation must fail closed.
        assertThat(msg(() -> db.inTx(null, null, c ->
                put(c, t, m.folderType, "f", Integer.MAX_VALUE, m.userType, u.toString(), null))))
                .contains("relation_not_found");
    }

    @Test
    void appAndAdminCannotForgeChangelogRows() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        String forge = "insert into authz.relationship_changes (seq, tenant_id, op, relationship_id, object_type_id, "
                + "object_id, relation_id, subject_type_id, subject_id, actor_type, txid) "
                + "values (9000000000000000000, ?, 'insert', 1, ?, 'f', ?, ?, 'x', 'system', '1'::xid8)";
        for (String role : new String[] {"rbac_app", "rbac_admin"}) {
            assertThat(sqlState(() -> db.inTx(role, t, c ->
                    exec(c, forge, t, m.folderType, m.folderEditor, m.userType))))
                    .as("insert as %s", role).isEqualTo("42501");
            assertThat(sqlState(() -> db.inTx(role, t, c ->
                    one(c, Long.class, "select nextval('authz.relationship_changes_seq_seq')"))))
                    .as("nextval as %s", role).isEqualTo("42501");
        }
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from authz.relationship_changes where tenant_id = ?", t)).isZero();
            return null;
        });
    }

    @Test
    void adminCannotRewriteChangelog() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        // rbac_admin's own tuple writes are still logged (through the definer trigger).
        db.inTx("rbac_admin", null, c -> put(c, t, m.folderType, "f", m.folderEditor, m.userType, u.toString(), null));
        assertThat(sqlState(() -> db.inTx("rbac_admin", null, c ->
                exec(c, "delete from authz.relationship_changes where tenant_id = ?", t)))).isEqualTo("42501");
        assertThat(sqlState(() -> db.inTx("rbac_admin", null, c ->
                exec(c, "update authz.relationship_changes set op = 'delete' where tenant_id = ?", t))))
                .isEqualTo("42501");
        long rows = db.inTx("rbac_admin", null, c ->
                count(c, "select count(*) from authz.relationship_changes where tenant_id = ? and op = 'insert'", t));
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void tuplesAndChangesAreInvisibleToOtherTenants() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        Model ma = canonicalModel(a);
        UUID ua = newUser(a);
        db.inTx("rbac_app", a, c -> put(c, a, ma.folderType, "f", ma.folderEditor, ma.userType, ua.toString(), null));
        db.inTx("rbac_app", b, c -> {
            assertThat(count(c, "select count(*) from authz.relationships")).isZero();
            assertThat(count(c, "select count(*) from authz.relationship_changes")).isZero();
            return null;
        });
        db.inTx("rbac_app", a, c -> {
            assertThat(count(c, "select count(*) from authz.relationships")).isEqualTo(1);
            assertThat(count(c, "select count(*) from authz.relationship_changes")).isEqualTo(1);
            return null;
        });
    }

    @Test
    void duplicateTupleRejectedEvenWithNullSubjectRelation() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        db.inTx("rbac_app", t, c -> put(c, t, m.folderType, "f", m.folderEditor, m.userType, u.toString(), null));
        assertThat(sqlState(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.folderType, "f", m.folderEditor, m.userType, u.toString(), null))))
                .isEqualTo("23505");
    }

    @Test
    void objectIdLongerThan256Rejected() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        db.inTx("rbac_app", t, c ->
                put(c, t, m.folderType, "x".repeat(256), m.folderEditor, m.userType, u.toString(), null));
        assertThat(sqlState(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.folderType, "x".repeat(257), m.folderEditor, m.userType, u.toString(), null))))
                .isEqualTo("23514");
        assertThat(sqlState(() -> db.inTx("rbac_app", t, c ->
                put(c, t, m.folderType, "", m.folderEditor, m.userType, u.toString(), null))))
                .isEqualTo("23514");
    }

    @Test
    void deletingUserRemovesTheirTuplesAndLogsDeletes() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID an = newUser(t);
        UUID other = newUser(t);
        UUID g = newGroup(t);
        db.inTx("rbac_app", t, c -> {
            put(c, t, m.groupType, g.toString(), m.groupMember, m.userType, an.toString(), null);
            put(c, t, m.folderType, "f", m.folderEditor, m.userType, an.toString(), null);
            put(c, t, m.groupType, g.toString(), m.groupMember, m.userType, other.toString(), null);
            return null;
        });
        db.inTx("rbac_app", t, c ->
                exec(c, "delete from identity.users where tenant_id = ? and id = ?", t, an));
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from authz.relationships where tenant_id = ? "
                    + "and subject_type_id = ? and subject_id = ?", t, m.userType, an.toString())).isZero();
            assertThat(count(c, "select count(*) from authz.relationships where tenant_id = ?", t)).isEqualTo(1);
            assertThat(changes(c, t, "delete")).isEqualTo(2);
            return null;
        });
    }

    @Test
    void deletingGroupAndApplicationRemovesTuplesOnBothSides() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        UUID g = newGroup(t);
        UUID app = newApplication(t);
        db.inTx("rbac_app", t, c -> {
            put(c, t, m.groupType, g.toString(), m.groupMember, m.userType, u.toString(), null);   // g as object
            put(c, t, m.folderType, "f", m.folderEditor, m.groupType, g.toString(), m.groupMember); // g as subject
            put(c, t, m.tenantType, t.toString(), m.tenantMember, m.applicationType, app.toString(), null);
            return null;
        });
        db.inTx("rbac_app", t, c -> {
            exec(c, "delete from identity.groups where tenant_id = ? and id = ?", t, g);
            assertThat(count(c, "select count(*) from authz.relationships where tenant_id = ?", t)).isEqualTo(1);
            exec(c, "delete from apps.applications where tenant_id = ? and id = ?", t, app);
            assertThat(count(c, "select count(*) from authz.relationships where tenant_id = ?", t)).isZero();
            return null;
        });
    }

    @Test
    void groupCycleIsStoredAndCascadeDeletes() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID a = newGroup(t);
        UUID b = newGroup(t);
        db.inTx("rbac_app", t, c -> {
            put(c, t, m.groupType, a.toString(), m.groupMember, m.groupType, b.toString(), m.groupMember);
            put(c, t, m.groupType, b.toString(), m.groupMember, m.groupType, a.toString(), m.groupMember);
            assertThat(count(c, "select count(*) from authz.relationships where tenant_id = ?", t)).isEqualTo(2);
            return null;
        });
        db.inTx("rbac_app", t, c -> {
            exec(c, "delete from identity.groups where tenant_id = ? and id = ?", t, a);
            assertThat(count(c, "select count(*) from authz.relationships where tenant_id = ? "
                    + "and ((object_type_id = ? and object_id = ?) or (subject_type_id = ? and subject_id = ?))",
                    t, m.groupType, a.toString(), m.groupType, a.toString())).isZero();
            assertThat(count(c, "select count(*) from authz.relationships where tenant_id = ?", t)).isZero();
            return null;
        });
    }

    @Test
    void entityDeletionOnlyTouchesItsOwnTenant() {
        UUID t = db.insertTenant();
        UUID other = db.insertTenant();
        Model m = canonicalModel(t);
        Model mo = canonicalModel(other);
        UUID u = newUser(t);
        UUID uo = newUser(other);
        db.inTx("rbac_app", t, c -> put(c, t, m.folderType, "f", m.folderEditor, m.userType, u.toString(), null));
        db.inTx("rbac_app", other, c ->
                put(c, other, mo.folderType, "f", mo.folderEditor, mo.userType, uo.toString(), null));
        db.inTx("rbac_app", t, c -> exec(c, "delete from identity.users where tenant_id = ? and id = ?", t, u));
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from authz.relationships where tenant_id = ?", t)).isZero();
            assertThat(count(c, "select count(*) from authz.relationships where tenant_id = ?", other)).isEqualTo(1);
            return null;
        });
    }

    @Test
    void changelogRecordsActorFromSession() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        UUID actor = newUser(t);
        db.inTx("rbac_app", t, c -> {
            one(c, String.class, "select set_config('app.actor_type', 'user', true)");
            one(c, String.class, "select set_config('app.actor_id', ?, true)", actor.toString());
            put(c, t, m.folderType, "f", m.folderEditor, m.userType, u.toString(), null);
            return null;
        });
        // actor settings empty: NULL
        db.inTx("rbac_app", t, c -> {
            one(c, String.class, "select set_config('app.actor_type', '', true)");
            put(c, t, m.folderType, "g", m.folderEditor, m.userType, u.toString(), null);
            return null;
        });
        db.inTx(null, null, c -> {
            assertThat(one(c, String.class, "select actor_type from authz.relationship_changes "
                    + "where tenant_id = ? and object_id = 'f'", t)).isEqualTo("user");
            assertThat(one(c, String.class, "select actor_id from authz.relationship_changes "
                    + "where tenant_id = ? and object_id = 'f'", t)).isEqualTo(actor.toString());
            assertThat(one(c, String.class, "select actor_type from authz.relationship_changes "
                    + "where tenant_id = ? and object_id = 'g'", t)).isNull();
            assertThat(one(c, String.class, "select actor_id from authz.relationship_changes "
                    + "where tenant_id = ? and object_id = 'g'", t)).isNull();
            assertThat(one(c, Boolean.class, "select txid is not null and relationship_id is not null "
                    + "from authz.relationship_changes where tenant_id = ? and object_id = 'g'", t)).isTrue();
            return null;
        });
    }

    @Test
    void updateIsLoggedAsDeleteThenInsert() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        long id = db.inTx("rbac_app", t, c ->
                put(c, t, m.folderType, "f", m.folderEditor, m.userType, u.toString(), null));
        db.inTx("rbac_app", t, c ->
                exec(c, "update authz.relationships set expires_at = now() + interval '1 day' "
                        + "where tenant_id = ? and id = ?", t, id));
        db.inTx(null, null, c -> {
            try (var ps = c.prepareStatement(
                    "select op, expires_at is null from authz.relationship_changes "
                    + "where tenant_id = ? and relationship_id = ? order by seq")) {
                ps.setObject(1, t);
                ps.setLong(2, id);
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("insert");
                    assertThat(rs.getBoolean(2)).isTrue();
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("delete");
                    assertThat(rs.getBoolean(2)).isTrue(); // old value
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("insert");
                    assertThat(rs.getBoolean(2)).isFalse(); // new value
                    assertThat(rs.next()).isFalse();
                }
            } catch (SQLException e) {
                throw Sql.sneaky(e);
            }
            return null;
        });
    }

    @Test
    void updateIsRevalidated() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        long id = db.inTx("rbac_app", t, c ->
                put(c, t, m.folderType, "f", m.folderEditor, m.userType, u.toString(), null));
        // repoint the stored tuple at the permission relation of the same type
        assertThat(msg(() -> db.inTx("rbac_app", t, c ->
                exec(c, "update authz.relationships set relation_id = ? where tenant_id = ? and id = ?",
                        m.folderEdit, t, id))))
                .contains("relation_is_permission");
    }

    @Test
    void appRoleCannotRewriteChangelog() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        db.inTx("rbac_app", t, c -> put(c, t, m.folderType, "f", m.folderEditor, m.userType, u.toString(), null));
        assertThat(sqlState(() -> db.inTx("rbac_app", t, c ->
                exec(c, "delete from authz.relationship_changes where tenant_id = ?", t)))).isEqualTo("42501");
        assertThat(sqlState(() -> db.inTx("rbac_app", t, c ->
                exec(c, "update authz.relationship_changes set op = 'delete' where tenant_id = ?", t))))
                .isEqualTo("42501");
        // and cannot touch a partition directly
        assertThat(sqlState(() -> db.inTx("rbac_app", t, c ->
                count(c, "select count(*) from authz.relationship_changes_default")))).isEqualTo("42501");
    }

    @Test
    void changelogIsPartitionedWithDefaultAndUpcomingMonths() {
        db.inTx(null, null, c -> {
            assertThat(count(c,
                    "select count(*) from pg_inherits where inhparent = 'authz.relationship_changes'::regclass"))
                    .isGreaterThanOrEqualTo(5); // default + current month + 3 ahead
            assertThat(one(c, Boolean.class, "select to_regclass('authz.relationship_changes_default') is not null"))
                    .isTrue();
            return null;
        });
    }

    @Test
    void relationInUseCannotBeDeleted() {
        UUID t = db.insertTenant();
        Model m = canonicalModel(t);
        UUID u = newUser(t);
        db.inTx("rbac_app", t, c -> put(c, t, m.folderType, "f", m.folderEditor, m.userType, u.toString(), null));
        assertThat(sqlState(() -> db.inTx(null, null, c ->
                exec(c, "delete from authz.relations where tenant_id = ? and id = ?", t, m.folderEditor))))
                .isEqualTo("23503");
    }
}
