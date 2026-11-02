package com.rbac_platform.testsupport;

import java.util.List;

/**
 * Starts one migrated embedded Postgres on a fixed port and keeps it up, so identity, authz and audit can share it
 * during local development (see {@code scripts/dev.sh}). Each service's own launcher would otherwise start a private
 * database, and the three would never see each other's data.
 *
 * <p>Port: first argument, else {@code DEV_DB_PORT}, else 54329. Data is gone when the process stops.
 */
public final class DevDatabase {

    private static final int DEFAULT_PORT = 54329;

    private DevDatabase() {
    }

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0].trim())
                : System.getenv("DEV_DB_PORT") != null ? Integer.parseInt(System.getenv("DEV_DB_PORT").trim())
                : DEFAULT_PORT;
        // MigratedDatabase registers the shutdown hook that closes Postgres.
        MigratedDatabase db = MigratedDatabase.startStandalone(port);
        for (String role : List.of("identity_app", "authz_app", "audit_app", "rbac_admin")) {
            AppDatabase.enableLogin(db, role);
        }
        System.out.println("DEV DB READY " + db.jdbcUrl());
        System.out.flush();
        Thread.currentThread().join();
    }
}
