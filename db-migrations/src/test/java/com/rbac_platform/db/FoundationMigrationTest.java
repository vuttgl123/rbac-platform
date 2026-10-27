package com.rbac_platform.db;

import static com.rbac_platform.testsupport.Sql.one;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.rbac_platform.testsupport.MigratedDatabase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FoundationMigrationTest {

    private static final List<String> PLATFORM_SCHEMAS =
            List.of("tenancy", "identity", "apps", "authz", "audit", "util");

    private final MigratedDatabase db = MigratedDatabase.get();

    @Test
    void schemasAreOwnedByRbacOwner() {
        db.inTx(null, null, c -> {
            for (String s : PLATFORM_SCHEMAS) {
                assertThat(one(c, String.class,
                        "select nspowner::regrole::text from pg_namespace where nspname = ?", s))
                        .as("owner of schema %s", s)
                        .isEqualTo("rbac_owner");
            }
            return null;
        });
    }

    @Test
    void uuidV7HasVersionVariantAndTimeOrder() throws Exception {
        UUID a = db.inTx(null, null, c -> one(c, UUID.class, "select util.uuid_v7()"));
        Thread.sleep(2);
        UUID b = db.inTx(null, null, c -> one(c, UUID.class, "select util.uuid_v7()"));
        assertThat(a.version()).isEqualTo(7);
        assertThat(a.variant()).isEqualTo(2);
        assertThat(a.toString().compareTo(b.toString())).isNegative();
        long ms = a.getMostSignificantBits() >>> 16;
        assertThat(ms).isCloseTo(System.currentTimeMillis(), within(5000L));
    }

    @Test
    void currentTenantIdIsNullWithoutContext() {
        assertThat(db.<UUID>inTx("rbac_app", null, c -> one(c, UUID.class, "select util.current_tenant_id()")))
                .isNull();
    }

    @Test
    void anonHasNoAccessToPlatformSchemas() {
        db.inTx(null, null, c -> {
            for (String role : List.of("anon", "authenticated")) {
                for (String s : PLATFORM_SCHEMAS) {
                    assertThat(one(c, Boolean.class,
                            "select has_schema_privilege(?, ?, 'USAGE')", role, s))
                            .as("%s USAGE on %s", role, s)
                            .isFalse();
                }
            }
            return null;
        });
    }

    @Test
    void extensionsAreInstalled() {
        db.inTx(null, null, c -> {
            for (String ext : List.of("citext", "pgcrypto")) {
                assertThat(one(c, Long.class,
                        "select count(*) from pg_extension where extname = ?", ext))
                        .as("extension %s", ext)
                        .isEqualTo(1L);
            }
            return null;
        });
    }
}
