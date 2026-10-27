package com.rbac_platform;

import com.rbac_platform.db.MigratedDatabase;
import org.springframework.boot.SpringApplication;

/**
 * Runs the application locally against a throwaway embedded Postgres with every migration applied,
 * connected as rbac_app exactly as in production: {@code ./mvnw spring-boot:test-run}.
 * Data is gone when the process stops.
 */
public class TestRbacPlatformApplication {

    public static void main(String[] args) {
        MigratedDatabase db = AppDatabase.enableAppLogin();
        // System properties outrank .env, so a filled-in DB_URL does not redirect this run to Supabase.
        System.setProperty("spring.datasource.url", db.jdbcUrl());
        System.setProperty("spring.datasource.username", "rbac_app");
        System.setProperty("spring.datasource.password", "unused-trust-auth");
        System.out.println("Embedded Postgres (superuser postgres, no password): " + db.jdbcUrl());
        SpringApplication.from(RbacPlatformApplication::main).run(args);
    }
}
