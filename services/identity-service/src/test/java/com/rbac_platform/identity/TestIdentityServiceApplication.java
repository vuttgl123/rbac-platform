package com.rbac_platform.identity;

import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import org.springframework.boot.SpringApplication;

/**
 * Runs the identity service locally against a throwaway embedded Postgres with every migration applied,
 * connected as identity_app exactly as in production: {@code ./mvnw -pl services/identity-service spring-boot:test-run}.
 * Data is gone when the process stops.
 */
public class TestIdentityServiceApplication {

    public static void main(String[] args) {
        MigratedDatabase db = AppDatabase.enableLogin("identity_app");
        // System properties outrank .env, so a filled-in DB_URL does not redirect this run to Supabase.
        System.setProperty("spring.datasource.url", db.jdbcUrl());
        System.setProperty("spring.datasource.username", "identity_app");
        System.setProperty("spring.datasource.password", "unused-trust-auth");
        System.out.println("Embedded Postgres (superuser postgres, no password): " + db.jdbcUrl());
        SpringApplication.from(IdentityServiceApplication::main).run(args);
    }
}
