package com.rbac_platform.db;

import static com.rbac_platform.testsupport.Sql.count;
import static com.rbac_platform.testsupport.Sql.errorMessage;
import static com.rbac_platform.testsupport.Sql.exec;
import static com.rbac_platform.testsupport.Sql.one;
import static com.rbac_platform.testsupport.Sql.sqlState;
import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.testsupport.MigratedDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** tenancy.create_tenant: atomic provisioning by rbac_admin only, and the seeded platform tenant. */
class TenantProvisioningTest {

    private static final String CREATE =
            "select tenant_id, admin_user_id from tenancy.create_tenant(?, ?, ?, ?, ?, ?)";

    private final MigratedDatabase db = MigratedDatabase.get();

    private static String rand() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String sha256Hex() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
    }

    private record Created(UUID tenantId, UUID adminUserId) {}

    private static Created create(Connection c, String slug, String email, String tokenHash, Instant expires,
            UUID actor) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement(CREATE)) {
            ps.setString(1, slug);
            ps.setString(2, "ACME");
            ps.setString(3, email);
            ps.setString(4, tokenHash);
            ps.setTimestamp(5, expires == null ? null : Timestamp.from(expires));
            ps.setObject(6, actor);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Created(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
            }
        }
    }

    @Test
    void createsTenantAdminTupleInvitationAndAuditAtomically() {
        String slug = "acme-" + rand();
        String hash = sha256Hex();
        UUID operator = UUID.randomUUID();
        Created created = db.inTx("rbac_admin", null, c ->
                create(c, slug, "admin@acme.test", hash, Instant.now().plus(7, ChronoUnit.DAYS), operator));

        // Verify as the superuser: nothing here depends on tenant context.
        db.inTx(null, null, c -> {
            UUID t = created.tenantId();
            UUID u = created.adminUserId();
            assertThat(one(c, UUID.class, "select id from tenancy.tenants where slug = ?", slug)).isEqualTo(t);
            assertThat(one(c, String.class,
                    "select status from identity.users where tenant_id = ? and id = ?", t, u)).isEqualTo("invited");
            assertThat(one(c, String.class,
                    "select email::text from identity.users where tenant_id = ? and id = ?", t, u))
                    .isEqualTo("admin@acme.test");

            assertThat(count(c, """
                    select count(*) from authz.relationships r
                    join authz.resource_types ot on ot.tenant_id = r.tenant_id and ot.id = r.object_type_id
                    join authz.relations rel on rel.tenant_id = r.tenant_id and rel.id = r.relation_id
                    join authz.resource_types st on st.tenant_id = r.tenant_id and st.id = r.subject_type_id
                    where r.tenant_id = ? and ot.key = 'tenant' and r.object_id = ?
                      and rel.key = 'admin' and st.key = 'user' and r.subject_id = ?
                      and r.subject_relation_id is null
                    """, t, t.toString(), u.toString())).isEqualTo(1);

            assertThat(count(c, "select count(*) from identity.one_time_tokens "
                    + "where tenant_id = ? and user_id = ? and purpose = 'invitation' and token_hash = ?",
                    t, u, hash)).isEqualTo(1);
            assertThat(count(c, "select count(*) from identity.one_time_tokens where tenant_id = ?", t))
                    .isEqualTo(1);

            assertThat(count(c, """
                    select count(*) from audit.events
                    where tenant_id = ? and action = 'tenant.created' and actor_type = 'operator'
                      and actor_id = ? and target_type = 'tenant' and target_id = ? and outcome = 'success'
                      and metadata ->> 'slug' = ? and metadata ->> 'admin_user_id' = ?
                    """, t, operator.toString(), t.toString(), slug, u.toString())).isEqualTo(1);

            assertThat(count(c, "select count(*) from authz.resource_types where tenant_id = ? and is_builtin "
                    + "and key in ('tenant', 'user', 'group', 'application')", t)).isEqualTo(4);

            // The changelog recorded the tuple with the operator as actor.
            assertThat(count(c, "select count(*) from authz.relationship_changes "
                    + "where tenant_id = ? and actor_type = 'operator' and actor_id = ?", t, operator.toString()))
                    .isEqualTo(1);
            return null;
        });
    }

    @Test
    void nullActorIsRecordedAsSystemAndTokenStepIsSkipped() {
        String slug = "sys-" + rand();
        Created created = db.inTx("rbac_admin", null, c ->
                create(c, slug, "admin@" + slug + ".test", null, null, null));
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from identity.one_time_tokens where tenant_id = ?",
                    created.tenantId())).isZero();
            assertThat(count(c, "select count(*) from audit.events where tenant_id = ? "
                    + "and action = 'tenant.created' and actor_type = 'system' and actor_id is null",
                    created.tenantId())).isEqualTo(1);
            return null;
        });
    }

    @Test
    void actorSettingsDoNotLeakIntoTheCallersTransaction() {
        String slug = "leak-" + rand();
        db.inTx("rbac_admin", null, c -> {
            exec(c, "select set_config('app.actor_type', 'user', true), set_config('app.actor_id', 'u-9', true)");
            create(c, slug, "admin@" + slug + ".test", null, null, UUID.randomUUID());
            assertThat(one(c, String.class, "select current_setting('app.actor_type', true)")).isEqualTo("user");
            assertThat(one(c, String.class, "select current_setting('app.actor_id', true)")).isEqualTo("u-9");
            return null;
        });
        // When unset before the call, it must read as unset/empty afterwards.
        db.inTx("rbac_admin", null, c -> {
            create(c, "leak2-" + rand(), "admin@leak2.test", null, null, UUID.randomUUID());
            assertThat(one(c, String.class, "select nullif(current_setting('app.actor_type', true), '')")).isNull();
            assertThat(one(c, String.class, "select nullif(current_setting('app.actor_id', true), '')")).isNull();
            return null;
        });
    }

    @Test
    void duplicateSlugLeavesNothingBehind() {
        String slug = "dup-" + rand();
        String second = "second-" + rand() + "@acme.test";
        db.inTx("rbac_admin", null, c -> create(c, slug, "first@" + slug + ".test", null, null, null));

        assertThat(sqlState(() -> db.inTx("rbac_admin", null, c ->
                create(c, slug, second, sha256Hex(), Instant.now().plus(1, ChronoUnit.DAYS), null))))
                .isEqualTo("23505");
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from identity.users where email = ?", second)).isZero();
            assertThat(count(c, "select count(*) from tenancy.tenants where slug = ?", slug)).isEqualTo(1);
            return null;
        });
    }

    @Test
    void invitationHashWithoutExpiryIsRejected() {
        String slug = "noexp-" + rand();
        assertThat(errorMessage(() -> db.inTx("rbac_admin", null, c ->
                create(c, slug, "admin@" + slug + ".test", sha256Hex(), null, null))))
                .contains("invitation_expiry_required");
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from tenancy.tenants where slug = ?", slug)).isZero();
            return null;
        });
    }

    @Test
    void appRoleCannotCreateTenant() {
        String slug = "app-" + rand();
        assertThat(sqlState(() -> db.inTx("rbac_app", null, c ->
                create(c, slug, "admin@" + slug + ".test", null, null, null)))).isEqualTo("42501");
    }

    @Test
    void platformTenantIsSeeded() {
        db.inTx(null, null, c -> {
            UUID t = one(c, UUID.class, "select id from tenancy.tenants where slug = 'platform'");
            assertThat(t).isNotNull();
            assertThat(one(c, String.class,
                    "select concat_ws(' ', settings -> 'mfa' ->> 'required', mfa_min) from tenancy.tenants where id = ?", t))
                    .isEqualTo("required required");
            UUID u = one(c, UUID.class,
                    "select id from identity.users where tenant_id = ? and email = 'operator@platform.test' "
                            + "and status = 'invited'", t);
            assertThat(u).isNotNull();
            assertThat(count(c, "select count(*) from identity.one_time_tokens where tenant_id = ?", t)).isZero();

            assertThat(count(c, """
                    select count(*) from authz.relations rel
                    join authz.resource_types rt on rt.tenant_id = rel.tenant_id and rt.id = rel.resource_type_id
                    where rel.tenant_id = ? and rt.key = 'tenant' and rel.key = 'operator'
                      and rel.kind = 'role' and rel.is_builtin
                    """, t)).isEqualTo(1);
            assertThat(count(c, """
                    select count(*) from authz.relation_subject_types s
                    join authz.relations rel on rel.tenant_id = s.tenant_id and rel.id = s.relation_id
                    join authz.resource_types st on st.tenant_id = s.tenant_id and st.id = s.subject_type_id
                    where s.tenant_id = ? and rel.key = 'operator' and st.key = 'user'
                      and s.subject_relation_id is null and not s.allow_wildcard and not s.allow_condition
                    """, t)).isEqualTo(1);
            assertThat(count(c, """
                    select count(*) from authz.relationships r
                    join authz.relations rel on rel.tenant_id = r.tenant_id and rel.id = r.relation_id
                    where r.tenant_id = ? and rel.key = 'operator' and r.object_id = ? and r.subject_id = ?
                      and r.subject_relation_id is null
                    """, t, t.toString(), u.toString())).isEqualTo(1);
            // The admin tuple from create_tenant exists too.
            assertThat(count(c, """
                    select count(*) from authz.relationships r
                    join authz.relations rel on rel.tenant_id = r.tenant_id and rel.id = r.relation_id
                    where r.tenant_id = ? and rel.key = 'admin' and r.subject_id = ?
                    """, t, u.toString())).isEqualTo(1);
            return null;
        });
    }

    @Test
    void operatorRelationCannotBeDeletedRenamedOrWidenedByTheApp() {
        UUID t = db.inTx(null, null, c -> one(c, UUID.class, "select id from tenancy.tenants where slug = 'platform'"));
        int operator = db.inTx(null, null, c -> one(c, Integer.class,
                "select rel.id from authz.relations rel join authz.resource_types rt "
                + "on rt.tenant_id = rel.tenant_id and rt.id = rel.resource_type_id "
                + "where rel.tenant_id = ? and rt.key = 'tenant' and rel.key = 'operator'", t));
        assertThat(errorMessage(() -> db.inTx("rbac_app", t, c -> exec(c,
                "update authz.relations set key = 'op2' where tenant_id = ? and id = ?", t, operator))))
                .contains("builtin_immutable");
        assertThat(errorMessage(() -> db.inTx("rbac_app", t, c -> exec(c,
                "delete from authz.relations where tenant_id = ? and id = ?", t, operator))))
                .contains("builtin_immutable");
        assertThat(errorMessage(() -> db.inTx("rbac_app", t, c -> exec(c,
                "update authz.relation_subject_types set allow_wildcard = true where tenant_id = ? and relation_id = ?",
                t, operator)))).contains("builtin_immutable");
        db.inTx(null, null, c -> {
            assertThat(count(c, "select count(*) from authz.relations where tenant_id = ? and id = ? "
                    + "and key = 'operator'", t, operator)).isEqualTo(1);
            return null;
        });
    }
}
