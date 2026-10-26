package com.rbac_platform.db;

import static com.rbac_platform.db.Sql.count;
import static com.rbac_platform.db.Sql.errorMessage;
import static com.rbac_platform.db.Sql.exec;
import static com.rbac_platform.db.Sql.one;
import static com.rbac_platform.db.Sql.sqlState;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthzModelTest {

    private final MigratedDatabase db = MigratedDatabase.get();

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private int insertType(UUID tenantId, String key) {
        return db.inTx(null, null, c -> insertType(c, tenantId, key));
    }

    private static int insertType(Connection c, UUID tenantId, String key) {
        return one(c, Integer.class,
                "insert into authz.resource_types (tenant_id, key, name) values (?, ?, ?) returning id",
                tenantId, key, key);
    }

    private int insertRelation(UUID tenantId, int typeId, String key, String kind) {
        return db.inTx(null, null, c -> one(c, Integer.class,
                "insert into authz.relations (tenant_id, resource_type_id, key, name, kind) "
                + "values (?, ?, ?, ?, ?) returning id",
                tenantId, typeId, key, key, kind));
    }

    private int insertRule(UUID tenantId, int typeId, int permissionId, String kind, String effect,
                           String target, Integer viaRelationId) {
        return db.inTx(null, null, c -> one(c, Integer.class,
                "insert into authz.permission_rules (tenant_id, resource_type_id, permission_id, kind, effect, "
                + "target_key, via_relation_id) values (?, ?, ?, ?, ?, ?, ?) returning id",
                tenantId, typeId, permissionId, kind, effect, target, viaRelationId));
    }

    private static long revision(Connection c, UUID tenantId) {
        return one(c, Long.class, "select authz_model_revision from tenancy.tenants where id = ?", tenantId);
    }

    @Test
    void newTenantGetsBuiltinModel() {
        UUID t = db.insertTenant();
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from authz.resource_types where tenant_id = ? and is_builtin "
                    + "and key in ('tenant','user','group','application')", t)).isEqualTo(4);
            assertThat(count(c, "select count(*) from authz.resource_types where tenant_id = ?", t)).isEqualTo(4);
            assertThat(count(c, "select count(*) from authz.relations r join authz.resource_types rt "
                    + "on rt.tenant_id = r.tenant_id and rt.id = r.resource_type_id "
                    + "where r.tenant_id = ? and r.is_builtin and ("
                    + "(rt.key = 'tenant' and r.key in ('admin','member') and r.kind = 'role') or "
                    + "(rt.key = 'group' and r.key = 'member' and r.kind = 'relationship'))", t)).isEqualTo(3);
            assertThat(count(c, "select count(*) from authz.relations where tenant_id = ?", t)).isEqualTo(3);

            // group.member allows (user, null) and (group, member)
            List<String> groupMember = subjectTypes(c, t, "group", "member");
            assertThat(groupMember).containsExactlyInAnyOrder("user", "group#member");
            // tenant roles allow user, group#member and application
            assertThat(subjectTypes(c, t, "tenant", "admin"))
                    .containsExactlyInAnyOrder("user", "group#member", "application");
            assertThat(subjectTypes(c, t, "tenant", "member"))
                    .containsExactlyInAnyOrder("user", "group#member", "application");
            return null;
        });
    }

    private static List<String> subjectTypes(Connection c, UUID t, String typeKey, String relationKey) {
        try (var ps = c.prepareStatement(
                "select st.key || coalesce('#' || sr.key, '') from authz.relation_subject_types rst "
                + "join authz.relations r on r.tenant_id = rst.tenant_id and r.id = rst.relation_id "
                + "join authz.resource_types rt on rt.tenant_id = r.tenant_id and rt.id = r.resource_type_id "
                + "join authz.resource_types st on st.tenant_id = rst.tenant_id and st.id = rst.subject_type_id "
                + "left join authz.relations sr on sr.tenant_id = rst.tenant_id and sr.id = rst.subject_relation_id "
                + "where rst.tenant_id = ? and rt.key = ? and r.key = ?")) {
            ps.setObject(1, t);
            ps.setString(2, typeKey);
            ps.setString(3, relationKey);
            var rs = ps.executeQuery();
            var out = new java.util.ArrayList<String>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (java.sql.SQLException e) {
            throw Sql.sneaky(e);
        }
    }

    @Test
    void builtinsCannotBeDeletedOrRenamed() {
        UUID t = db.insertTenant();
        assertThat(errorMessage(() -> db.inTx(null, null, c -> exec(c,
                "delete from authz.resource_types where tenant_id = ? and key = 'group'", t))))
                .contains("builtin_immutable");
        assertThat(errorMessage(() -> db.inTx(null, null, c -> exec(c,
                "update authz.resource_types set key = 'team' where tenant_id = ? and key = 'group'", t))))
                .contains("builtin_immutable");
        assertThat(errorMessage(() -> db.inTx(null, null, c -> exec(c,
                "delete from authz.relations where tenant_id = ? and key = 'admin'", t))))
                .contains("builtin_immutable");
        assertThat(errorMessage(() -> db.inTx(null, null, c -> exec(c,
                "update authz.relations set key = 'owner' where tenant_id = ? and key = 'admin'", t))))
                .contains("builtin_immutable");
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "delete from authz.resource_types where tenant_id = ? and key = 'group'", t))))
                .isEqualTo("23514");
    }

    @Test
    void builtinCannotBeDemotedButOtherColumnsCanChange() {
        UUID t = db.insertTenant();
        assertThat(errorMessage(() -> db.inTx(null, null, c -> exec(c,
                "update authz.resource_types set is_builtin = false where tenant_id = ? and key = 'user'", t))))
                .contains("builtin_immutable");
        assertThat(errorMessage(() -> db.inTx(null, null, c -> exec(c,
                "update authz.relations set is_builtin = false where tenant_id = ? and key = 'member'", t))))
                .contains("builtin_immutable");
        db.inTx(null, null, c -> {
            exec(c, "update authz.resource_types set name = 'Users', description = 'people' "
                    + "where tenant_id = ? and key = 'user'", t);
            assertThat(one(c, String.class,
                    "select name from authz.resource_types where tenant_id = ? and key = 'user'", t))
                    .isEqualTo("Users");
            return null;
        });
    }

    @Test
    void customRowsAreNotProtected() {
        UUID t = db.insertTenant();
        int folder = insertType(t, "folder");
        int editor = insertRelation(t, folder, "editor", "role");
        db.inTx(null, null, c -> {
            exec(c, "update authz.relations set key = 'writer' where tenant_id = ? and id = ?", t, editor);
            exec(c, "delete from authz.relations where tenant_id = ? and id = ?", t, editor);
            exec(c, "update authz.resource_types set key = 'dir' where tenant_id = ? and id = ?", t, folder);
            exec(c, "delete from authz.resource_types where tenant_id = ? and id = ?", t, folder);
            return null;
        });
    }

    @Test
    void relationKeyUniquePerResourceType() {
        UUID t = db.insertTenant();
        int folder = insertType(t, "folder");
        int document = insertType(t, "document");
        insertRelation(t, folder, "editor", "role");
        assertThat(sqlState(() -> insertRelation(t, folder, "editor", "role"))).isEqualTo("23505");
        insertRelation(t, document, "editor", "role");
    }

    @Test
    void resourceTypeAndConditionKeysUniquePerTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        insertType(a, "folder");
        assertThat(sqlState(() -> insertType(a, "folder"))).isEqualTo("23505");
        insertType(b, "folder");

        db.inTx(null, null, c -> exec(c,
                "insert into authz.conditions (tenant_id, key, expression) values (?, 'c1', 'true')", a));
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into authz.conditions (tenant_id, key, expression) values (?, 'c1', 'false')", a))))
                .isEqualTo("23505");
        db.inTx(null, null, c -> exec(c,
                "insert into authz.conditions (tenant_id, key, expression) values (?, 'c1', 'true')", b));
    }

    @Test
    void relationKindIsChecked() {
        UUID t = db.insertTenant();
        int folder = insertType(t, "folder");
        assertThat(sqlState(() -> insertRelation(t, folder, "x", "bogus"))).isEqualTo("23514");
        insertRelation(t, folder, "r", "role");
        insertRelation(t, folder, "p", "relationship");
        insertRelation(t, folder, "q", "permission");
    }

    @Test
    void conditionParametersDefaultToEmptyObject() {
        UUID t = db.insertTenant();
        db.inTx(null, null, c -> {
            exec(c, "insert into authz.conditions (tenant_id, key, expression) values (?, 'c', 'amount < 5')", t);
            assertThat(one(c, String.class,
                    "select parameters::text from authz.conditions where tenant_id = ? and key = 'c'", t))
                    .isEqualTo("{}");
            return null;
        });
    }

    @Test
    void ruleMustTargetPermissionKind() {
        UUID t = db.insertTenant();
        int document = insertType(t, "document");
        int editor = insertRelation(t, document, "editor", "role");
        int edit = insertRelation(t, document, "edit", "permission");
        assertThat(sqlState(() -> insertRule(t, document, editor, "computed", "allow", "editor", null)))
                .isEqualTo("23503");
        insertRule(t, document, edit, "computed", "allow", "editor", null);
    }

    @Test
    void ruleResourceTypeMustMatchPermissionResourceType() {
        UUID t = db.insertTenant();
        int document = insertType(t, "document");
        int folder = insertType(t, "folder");
        int edit = insertRelation(t, document, "edit", "permission");
        assertThat(sqlState(() -> insertRule(t, folder, edit, "computed", "allow", "editor", null)))
                .isEqualTo("23503");
    }

    @Test
    void viaRuleRequiresViaRelation() {
        UUID t = db.insertTenant();
        int document = insertType(t, "document");
        int edit = insertRelation(t, document, "edit", "permission");
        int parent = insertRelation(t, document, "parent", "relationship");
        assertThat(sqlState(() -> insertRule(t, document, edit, "via", "allow", "edit", null)))
                .isEqualTo("23514");
        assertThat(sqlState(() -> insertRule(t, document, edit, "computed", "allow", "editor", parent)))
                .isEqualTo("23514");
        insertRule(t, document, edit, "via", "allow", "edit", parent);
    }

    @Test
    void viaRelationMustBelongToSameResourceType() {
        UUID t = db.insertTenant();
        int document = insertType(t, "document");
        int folder = insertType(t, "folder");
        int edit = insertRelation(t, document, "edit", "permission");
        int folderParent = insertRelation(t, folder, "parent", "relationship");
        assertThat(sqlState(() -> insertRule(t, document, edit, "via", "allow", "edit", folderParent)))
                .isEqualTo("23503");
    }

    @Test
    void ruleKindIsChecked() {
        UUID t = db.insertTenant();
        int document = insertType(t, "document");
        int edit = insertRelation(t, document, "edit", "permission");
        assertThat(sqlState(() -> insertRule(t, document, edit, "magic", "allow", "editor", null)))
                .isEqualTo("23514");
        insertRule(t, document, edit, "tenant", "allow", "member", null);
    }

    @Test
    void effectIsAllowOrDeny() {
        UUID t = db.insertTenant();
        int document = insertType(t, "document");
        int edit = insertRelation(t, document, "edit", "permission");
        assertThat(sqlState(() -> insertRule(t, document, edit, "computed", "block", "blocked", null)))
                .isEqualTo("23514");
    }

    @Test
    void effectDefaultsToAllow() {
        UUID t = db.insertTenant();
        int document = insertType(t, "document");
        int edit = insertRelation(t, document, "edit", "permission");
        int rule = db.inTx(null, null, c -> one(c, Integer.class,
                "insert into authz.permission_rules (tenant_id, resource_type_id, permission_id, kind, target_key) "
                + "values (?, ?, ?, 'computed', 'editor') returning id", t, document, edit));
        db.inTx(null, null, c -> {
            assertThat(one(c, String.class,
                    "select effect from authz.permission_rules where tenant_id = ? and id = ?", t, rule))
                    .isEqualTo("allow");
            return null;
        });
    }

    @Test
    void denyRuleIsAccepted() {
        UUID t = db.insertTenant();
        int document = insertType(t, "document");
        int edit = insertRelation(t, document, "edit", "permission");
        insertRelation(t, document, "blocked", "role");
        insertRule(t, document, edit, "computed", "deny", "blocked", null);
    }

    @Test
    void ruleConditionMustBelongToSameTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        int document = insertType(a, "document");
        int edit = insertRelation(a, document, "edit", "permission");
        int condB = db.inTx(null, null, c -> one(c, Integer.class,
                "insert into authz.conditions (tenant_id, key, expression) values (?, 'c', 'true') returning id", b));
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into authz.permission_rules (tenant_id, resource_type_id, permission_id, kind, "
                + "target_key, condition_id) values (?, ?, ?, 'computed', 'editor', ?)", a, document, edit, condB))))
                .isEqualTo("23503");
    }

    @Test
    void modelChangeBumpsRevision() {
        UUID t = db.insertTenant();
        long before = db.inTx(null, null, c -> revision(c, t));
        insertType(t, "folder");
        long after = db.inTx(null, null, c -> revision(c, t));
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    void everyModelTableBumpsRevisionOnInsertUpdateAndDelete() {
        UUID t = db.insertTenant();
        db.inTx(null, null, c -> {
            long r = revision(c, t);
            int document = insertType(c, t, "document");
            assertThat(revision(c, t)).isEqualTo(r += 1);
            int edit = one(c, Integer.class, "insert into authz.relations (tenant_id, resource_type_id, key, name, kind) "
                    + "values (?, ?, 'edit', 'edit', 'permission') returning id", t, document);
            assertThat(revision(c, t)).isEqualTo(r += 1);
            int editor = one(c, Integer.class, "insert into authz.relations (tenant_id, resource_type_id, key, name, kind) "
                    + "values (?, ?, 'editor', 'editor', 'role') returning id", t, document);
            assertThat(revision(c, t)).isEqualTo(r += 1);
            int user = one(c, Integer.class,
                    "select id from authz.resource_types where tenant_id = ? and key = 'user'", t);
            int rst = one(c, Integer.class, "insert into authz.relation_subject_types (tenant_id, relation_id, "
                    + "subject_type_id) values (?, ?, ?) returning id", t, editor, user);
            assertThat(revision(c, t)).isEqualTo(r += 1);
            int cond = one(c, Integer.class,
                    "insert into authz.conditions (tenant_id, key, expression) values (?, 'c', 'true') returning id", t);
            assertThat(revision(c, t)).isEqualTo(r += 1);
            int rule = one(c, Integer.class, "insert into authz.permission_rules (tenant_id, resource_type_id, "
                    + "permission_id, kind, target_key) values (?, ?, ?, 'computed', 'editor') returning id",
                    t, document, edit);
            assertThat(revision(c, t)).isEqualTo(r += 1);

            exec(c, "update authz.permission_rules set condition_id = ? where tenant_id = ? and id = ?", cond, t, rule);
            assertThat(revision(c, t)).isEqualTo(r += 1);
            exec(c, "update authz.conditions set expression = 'false' where tenant_id = ? and id = ?", t, cond);
            assertThat(revision(c, t)).isEqualTo(r += 1);

            exec(c, "delete from authz.permission_rules where tenant_id = ? and id = ?", t, rule);
            assertThat(revision(c, t)).isEqualTo(r += 1);
            exec(c, "delete from authz.relation_subject_types where tenant_id = ? and id = ?", t, rst);
            assertThat(revision(c, t)).isEqualTo(r += 1);
            exec(c, "delete from authz.conditions where tenant_id = ? and id = ?", t, cond);
            assertThat(revision(c, t)).isEqualTo(r += 1);
            exec(c, "delete from authz.relations where tenant_id = ? and id = ?", t, editor);
            assertThat(revision(c, t)).isEqualTo(r += 1);
            return null;
        });
    }

    @Test
    void modelChangeBumpsOnlyOwnTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        long bBefore = db.inTx(null, null, c -> revision(c, b));
        insertType(a, "folder");
        long bAfter = db.inTx(null, null, c -> revision(c, b));
        assertThat(bAfter).isEqualTo(bBefore);
    }

    @Test
    void appRoleCanEditOwnModelAndBumpRevision() {
        UUID t = db.insertTenant();
        long before = db.inTx("rbac_app", t, c -> revision(c, t));
        db.inTx("rbac_app", t, c -> insertType(c, t, "folder"));
        long after = db.inTx("rbac_app", t, c -> revision(c, t));
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    void appRoleCannotSeedOrWriteAnotherTenantsModel() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        assertThat(sqlState(() -> db.inTx("rbac_app", a, c ->
                one(c, Object.class, "select authz.seed_builtin_model(?)", a)))).isEqualTo("42501");
        assertThat(sqlState(() -> db.inTx("rbac_app", a, c -> insertType(c, b, "folder")))).isEqualTo("42501");
    }

    @Test
    void modelRowsCannotReferenceAnotherTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        int docA = insertType(a, "document");
        insertRelation(a, docA, "edit", "permission");
        int docB = insertType(b, "document");
        int editB = insertRelation(b, docB, "edit", "permission");
        int editorB = insertRelation(b, docB, "editor", "role");
        // rule in tenant a pointing at tenant b's permission
        assertThat(sqlState(() -> insertRule(a, docB, editB, "computed", "allow", "editor", null)))
                .isEqualTo("23503");
        assertThat(sqlState(() -> insertRule(a, docA, editB, "computed", "allow", "editor", null)))
                .isEqualTo("23503");
        // relation in tenant a pointing at tenant b's type
        assertThat(sqlState(() -> insertRelation(a, docB, "x", "role"))).isEqualTo("23503");
        // subject type in tenant a pointing at tenant b's relation / type
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into authz.relation_subject_types (tenant_id, relation_id, subject_type_id) "
                + "values (?, ?, ?)", a, editorB, docA)))).isEqualTo("23503");
    }

    @Test
    void subjectTypeIsUniqueIncludingNullSubjectRelation() {
        UUID t = db.insertTenant();
        int folder = insertType(t, "folder");
        int editor = insertRelation(t, folder, "editor", "role");
        int user = db.inTx(null, null, c -> one(c, Integer.class,
                "select id from authz.resource_types where tenant_id = ? and key = 'user'", t));
        String sql = "insert into authz.relation_subject_types (tenant_id, relation_id, subject_type_id) "
                + "values (?, ?, ?)";
        db.inTx(null, null, c -> exec(c, sql, t, editor, user));
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c, sql, t, editor, user)))).isEqualTo("23505");
    }

    @Test
    void subjectRelationMustBelongToSubjectType() {
        UUID t = db.insertTenant();
        int folder = insertType(t, "folder");
        int editor = insertRelation(t, folder, "editor", "role");
        db.inTx(null, null, c -> {
            int group = one(c, Integer.class,
                    "select id from authz.resource_types where tenant_id = ? and key = 'group'", t);
            int user = one(c, Integer.class,
                    "select id from authz.resource_types where tenant_id = ? and key = 'user'", t);
            int groupMember = one(c, Integer.class,
                    "select id from authz.relations where tenant_id = ? and resource_type_id = ? and key = 'member'",
                    t, group);
            String sql = "insert into authz.relation_subject_types (tenant_id, relation_id, subject_type_id, "
                    + "subject_relation_id) values (?, ?, ?, ?)";
            // group#member is valid; user#member is not (member belongs to group)
            exec(c, sql, t, editor, group, groupMember);
            assertThat(sqlState(() -> {
                try (var sp = new SavepointGuard(c)) {
                    exec(c, sql, t, editor, user, groupMember);
                }
            })).isEqualTo("23503");
            return null;
        });
    }

    @Test
    void allowFlagsDefaultToFalse() {
        UUID t = db.insertTenant();
        int folder = insertType(t, "folder");
        int editor = insertRelation(t, folder, "editor", "role");
        db.inTx(null, null, c -> {
            int user = one(c, Integer.class,
                    "select id from authz.resource_types where tenant_id = ? and key = 'user'", t);
            exec(c, "insert into authz.relation_subject_types (tenant_id, relation_id, subject_type_id) "
                    + "values (?, ?, ?)", t, editor, user);
            assertThat(count(c, "select count(*) from authz.relation_subject_types where tenant_id = ? "
                    + "and relation_id = ? and not allow_wildcard and not allow_condition", t, editor)).isEqualTo(1);
            return null;
        });
    }

    @Test
    void deletingRelationCascadesToSubjectTypesAndRules() {
        UUID t = db.insertTenant();
        int document = insertType(t, "document");
        int edit = insertRelation(t, document, "edit", "permission");
        int editor = insertRelation(t, document, "editor", "role");
        insertRule(t, document, edit, "computed", "allow", "editor", null);
        db.inTx(null, null, c -> {
            int user = one(c, Integer.class,
                    "select id from authz.resource_types where tenant_id = ? and key = 'user'", t);
            exec(c, "insert into authz.relation_subject_types (tenant_id, relation_id, subject_type_id) "
                    + "values (?, ?, ?)", t, editor, user);
            exec(c, "delete from authz.relations where tenant_id = ? and id = ?", t, editor);
            assertThat(count(c, "select count(*) from authz.relation_subject_types where tenant_id = ? "
                    + "and relation_id = ?", t, editor)).isZero();
            exec(c, "delete from authz.relations where tenant_id = ? and id = ?", t, edit);
            assertThat(count(c, "select count(*) from authz.permission_rules where tenant_id = ?", t)).isZero();
            return null;
        });
    }

    @Test
    void usedViaRelationConditionAndTypesAreRestricted() {
        UUID t = db.insertTenant();
        int document = insertType(t, "document");
        int edit = insertRelation(t, document, "edit", "permission");
        int parent = insertRelation(t, document, "parent", "relationship");
        int cond = db.inTx(null, null, c -> one(c, Integer.class,
                "insert into authz.conditions (tenant_id, key, expression) values (?, 'c', 'true') returning id", t));
        db.inTx(null, null, c -> exec(c,
                "insert into authz.permission_rules (tenant_id, resource_type_id, permission_id, kind, target_key, "
                + "via_relation_id, condition_id) values (?, ?, ?, 'via', 'edit', ?, ?)",
                t, document, edit, parent, cond));
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "delete from authz.relations where tenant_id = ? and id = ?", t, parent)))).isEqualTo("23503");
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "delete from authz.conditions where tenant_id = ? and id = ?", t, cond)))).isEqualTo("23503");
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "delete from authz.resource_types where tenant_id = ? and id = ?", t, document)))).isEqualTo("23503");
    }

    @Test
    void deletingApplicationDetachesResourceTypeButKeepsTenant() {
        UUID t = db.insertTenant();
        UUID app = db.inTx(null, null, c -> one(c, UUID.class,
                "insert into apps.applications (tenant_id, client_id, name, type, token_endpoint_auth_method) "
                + "values (?, ?, 'App', 'web', 'none') returning id", t, "client-" + rand()));
        int type = db.inTx(null, null, c -> one(c, Integer.class,
                "insert into authz.resource_types (tenant_id, key, name, application_id) "
                + "values (?, 'crm/document', 'Doc', ?) returning id", t, app));
        db.inTx(null, null, c -> exec(c, "delete from apps.applications where tenant_id = ? and id = ?", t, app));
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from authz.resource_types where tenant_id = ? and id = ? "
                    + "and application_id is null", t, type)).isEqualTo(1);
            return null;
        });
    }

    @Test
    void resourceTypeApplicationMustBelongToSameTenant() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        UUID appB = db.inTx(null, null, c -> one(c, UUID.class,
                "insert into apps.applications (tenant_id, client_id, name, type, token_endpoint_auth_method) "
                + "values (?, ?, 'App', 'web', 'none') returning id", b, "client-" + rand()));
        assertThat(sqlState(() -> db.inTx(null, null, c -> exec(c,
                "insert into authz.resource_types (tenant_id, key, name, application_id) values (?, 'x', 'x', ?)",
                a, appB)))).isEqualTo("23503");
    }

    @Test
    void updatedAtIsMaintained() {
        UUID t = db.insertTenant();
        int folder = insertType(t, "folder");
        db.inTx(null, null, c -> {
            exec(c, "update authz.resource_types set name = 'Folders' where tenant_id = ? and id = ?", t, folder);
            assertThat(count(c, "select count(*) from authz.resource_types where tenant_id = ? and id = ? "
                    + "and updated_at > created_at", t, folder)).isEqualTo(1);
            return null;
        });
    }

    @Test
    void tenantsAreIsolatedByRowLevelSecurity() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        insertType(b, "secret");
        db.inTx("rbac_app", a, c -> {
            assertThat(count(c, "select count(*) from authz.resource_types where key = 'secret'")).isZero();
            assertThat(count(c, "select count(*) from authz.resource_types where tenant_id = ?", a)).isEqualTo(4);
            return null;
        });
    }

    @Test
    void seedingIsRestrictedToOwnerAndAdmin() {
        db.insertTenant();
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                    + "where n.nspname = 'authz' and p.proname in ('seed_builtin_model','on_tenant_created') "
                    + "and (has_function_privilege('rbac_app', p.oid, 'EXECUTE') "
                    + "or has_function_privilege('public', p.oid, 'EXECUTE'))")).isZero();
            return null;
        });
    }

    /** Savepoint so a failing statement does not abort the surrounding test transaction. */
    private static final class SavepointGuard implements AutoCloseable {
        private final Connection c;
        private final java.sql.Savepoint sp;

        SavepointGuard(Connection c) {
            this.c = c;
            try {
                this.sp = c.setSavepoint();
            } catch (java.sql.SQLException e) {
                throw Sql.sneaky(e);
            }
        }

        @Override
        public void close() throws java.sql.SQLException {
            // Reached only when the guarded block threw; roll back to keep the transaction usable.
            c.rollback(sp);
        }
    }
}
