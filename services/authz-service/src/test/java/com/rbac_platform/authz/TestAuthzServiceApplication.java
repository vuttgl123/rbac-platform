package com.rbac_platform.authz;

import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import org.springframework.boot.SpringApplication;

/**
 * Runs the authz service locally against a throwaway embedded Postgres with every migration applied,
 * connected as authz_app exactly as in production: {@code ./mvnw -q install -DskipTests && ./mvnw -pl services/authz-service spring-boot:test-run}
 * (the install puts the sibling modules, including the db-migrations test-jar, in ~/.m2; repeat it after editing them).
 * Data is gone when the process stops.
 */
public class TestAuthzServiceApplication {

    public static void main(String[] args) {
        MigratedDatabase db = AppDatabase.enableLogin("authz_app");
        // System properties outrank .env, so a filled-in DB_URL does not redirect this run to Supabase.
        System.setProperty("spring.datasource.url", db.jdbcUrl());
        System.setProperty("spring.datasource.username", "authz_app");
        System.setProperty("spring.datasource.password", "unused-trust-auth");
        System.out.println("Embedded Postgres (superuser postgres, no password): " + db.jdbcUrl());
        SpringApplication.from(AuthzServiceApplication::main).run(args);
    }
}
