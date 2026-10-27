package com.rbac_platform.audit;

import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import org.springframework.boot.SpringApplication;

/**
 * Runs the audit service locally against a throwaway embedded Postgres with every migration applied,
 * connected as audit_app exactly as in production: {@code ./mvnw -pl services/audit-service spring-boot:test-run}.
 * Data is gone when the process stops.
 */
public class TestAuditServiceApplication {

    public static void main(String[] args) {
        MigratedDatabase db = AppDatabase.enableLogin("audit_app");
        // System properties outrank .env, so a filled-in DB_URL does not redirect this run to Supabase.
        System.setProperty("spring.datasource.url", db.jdbcUrl());
        System.setProperty("spring.datasource.username", "audit_app");
        System.setProperty("spring.datasource.password", "unused-trust-auth");
        System.out.println("Embedded Postgres (superuser postgres, no password): " + db.jdbcUrl());
        SpringApplication.from(AuditServiceApplication::main).run(args);
    }
}
