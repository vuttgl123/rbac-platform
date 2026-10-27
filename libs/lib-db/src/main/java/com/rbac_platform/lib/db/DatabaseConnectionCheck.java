package com.rbac_platform.lib.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Opens a connection at startup so a wrong URL or password fails the boot instead of the first request,
 * and refuses roles that bypass row-level security: tenant isolation lives in RLS, so a service must
 * never connect as postgres or another superuser. It also refuses any role other than the one the
 * service expects (rbac.db.expected-role), so a service cannot run with another service's grants.
 */
public class DatabaseConnectionCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionCheck.class);

    private final JdbcClient jdbc;
    private final String expectedRole;

    public DatabaseConnectionCheck(JdbcClient jdbc, String expectedRole) {
        if (expectedRole == null || expectedRole.isBlank()) {
            throw new IllegalStateException("rbac.db.expected-role is not set: each service must name the database role it connects as");
        }
        this.jdbc = jdbc;
        this.expectedRole = expectedRole;
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
                                + " Connect as a role without SUPERUSER or BYPASSRLS (this service expects '" + expectedRole + "').");
                    }
                    if (!user.equals(expectedRole)) {
                        throw new IllegalStateException("This service expects database role '" + expectedRole
                                + "' but is connected as '" + user + "'. Check spring.datasource.username.");
                    }
                    return new ConnectionInfo(user, rs.getString(2), rs.getString(3));
                })
                .single();
    }
}
