package com.rbac_platform.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Opens a connection at startup so a wrong URL or password fails the boot instead of the first request,
 * and refuses roles that bypass row-level security: tenant isolation lives in RLS, so the service must
 * connect as rbac_app, never as postgres or another superuser.
 */
@Component
public class DatabaseConnectionCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionCheck.class);

    private final JdbcClient jdbc;

    public DatabaseConnectionCheck(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ConnectionInfo(String user, String database, String serverVersion) {
    }

    @Override
    public void run(ApplicationArguments args) {
        ConnectionInfo info = verify();
        log.info("Connected to database '{}' as '{}' (PostgreSQL {})", info.database(), info.user(), info.serverVersion());
    }

    public ConnectionInfo verify() {
        return jdbc.sql("""
                select current_user, current_database(), current_setting('server_version'),
                       r.rolsuper or r.rolbypassrls as bypasses_rls
                from pg_roles r
                where r.rolname = current_user
                """)
                .query((rs, i) -> {
                    String user = rs.getString(1);
                    if (rs.getBoolean(4)) {
                        throw new IllegalStateException("Refusing to run as database role '" + user
                                + "': it bypasses row-level security, so tenants would see each other's data."
                                + " Connect as rbac_app (DB_APP_USER / DB_APP_PASSWORD).");
                    }
                    return new ConnectionInfo(user, rs.getString(2), rs.getString(3));
                })
                .single();
    }
}
