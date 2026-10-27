package com.rbac_platform.testsupport;

import java.util.regex.Pattern;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Points a service's data source at the shared migrated embedded Postgres, logged in as one of the platform roles. */
public final class AppDatabase {

    private static final Pattern ROLE_NAME = Pattern.compile("[a-z_][a-z0-9_]*");

    private AppDatabase() {
    }

    /** Use from a {@code @DynamicPropertySource} method. */
    public static void register(DynamicPropertyRegistry registry, String role) {
        MigratedDatabase db = enableLogin(role);
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", () -> role);
        registry.add("spring.datasource.password", () -> "unused-trust-auth");
        registry.add("rbac.db.expected-role", () -> role);
    }

    /** Platform roles are NOLOGIN after migration (logins are bootstrapped out of band); local connections are trusted. */
    public static MigratedDatabase enableLogin(String role) {
        if (!ROLE_NAME.matcher(role).matches()) {
            throw new IllegalArgumentException("Not a plain role name: " + role);
        }
        MigratedDatabase db = MigratedDatabase.get();
        db.inTx(null, null, c -> Sql.exec(c, "ALTER ROLE " + role + " LOGIN"));
        return db;
    }
}
