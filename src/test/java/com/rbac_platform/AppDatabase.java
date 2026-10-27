package com.rbac_platform;

import com.rbac_platform.db.MigratedDatabase;
import com.rbac_platform.db.Sql;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Points the application's data source at the shared migrated embedded Postgres, logged in as rbac_app. */
public final class AppDatabase {

    private AppDatabase() {
    }

    /** Use from a {@code @DynamicPropertySource} method. */
    public static void register(DynamicPropertyRegistry registry) {
        MigratedDatabase db = enableAppLogin();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", () -> "rbac_app");
        registry.add("spring.datasource.password", () -> "unused-trust-auth");
    }

    /** rbac_app is NOLOGIN after migration (logins are bootstrapped out of band); local connections are trusted. */
    public static MigratedDatabase enableAppLogin() {
        MigratedDatabase db = MigratedDatabase.get();
        db.inTx(null, null, c -> Sql.exec(c, "ALTER ROLE rbac_app LOGIN"));
        return db;
    }
}
