package com.rbac_platform.db;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * One embedded Postgres per test JVM with every Flyway migration applied.
 * Connections are superuser; {@link #inTx} drops to a platform role per transaction.
 */
public final class MigratedDatabase {

    private static final Pattern ROLE_NAME = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final String SLUG_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static volatile MigratedDatabase instance;

    private final DataSource dataSource;

    private MigratedDatabase(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static MigratedDatabase get() {
        MigratedDatabase db = instance;
        if (db == null) {
            synchronized (MigratedDatabase.class) {
                db = instance;
                if (db == null) {
                    db = start();
                    instance = db;
                }
            }
        }
        return db;
    }

    private static MigratedDatabase start() {
        try {
            EmbeddedPostgres pg = EmbeddedPostgres.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (IOException ignored) {
                    // best effort on JVM exit
                }
            }));
            DataSource ds = pg.getPostgresDatabase();
            // Simulate Supabase API roles, which the migration must lock out.
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE ROLE anon NOLOGIN");
                s.execute("CREATE ROLE authenticated NOLOGIN");
                s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') "
                        + "THEN CREATE ROLE service_role NOLOGIN; END IF; END $$");
            }
            Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .defaultSchema("flyway")
                    .createSchemas(true)
                    .placeholders(Map.of("platform_operator_email", "operator@platform.test"))
                    .load()
                    .migrate();
            return new MigratedDatabase(ds);
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("failed to start migrated embedded Postgres", e);
        }
    }

    /** Superuser data source. */
    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * Runs the work in one transaction and commits; rolls back if the work throws.
     *
     * @param role     role for {@code SET LOCAL ROLE}, or null to stay superuser
     * @param tenantId value for {@code app.tenant_id} (transaction-local), or null to leave it unset
     */
    public <T> T inTx(String role, UUID tenantId, SqlWork<T> work) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (role != null) {
                    if (!ROLE_NAME.matcher(role).matches()) {
                        throw new IllegalArgumentException("invalid role name: " + role);
                    }
                    try (Statement s = c.createStatement()) {
                        s.execute("SET LOCAL ROLE " + role);
                    }
                }
                if (tenantId != null) {
                    try (PreparedStatement ps = c.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                        ps.setString(1, tenantId.toString());
                        ps.execute();
                    }
                }
                T result = work.run(c);
                c.commit();
                return result;
            } catch (Throwable t) {
                try {
                    c.rollback();
                } catch (SQLException suppressed) {
                    t.addSuppressed(suppressed);
                }
                throw t;
            }
        } catch (SQLException e) {
            throw Sql.sneaky(e);
        }
    }

    /** Superuser insert of a tenant with a random slug; needs tenancy.tenants (Task 2). */
    public UUID insertTenant() {
        StringBuilder slug = new StringBuilder("t-");
        for (int i = 0; i < 8; i++) {
            slug.append(SLUG_CHARS.charAt(ThreadLocalRandom.current().nextInt(SLUG_CHARS.length())));
        }
        String s = slug.toString();
        return inTx(null, null, c -> Sql.one(c, UUID.class,
                "insert into tenancy.tenants (slug, name) values (?, ?) returning id", s, s));
    }

    /** Superuser insert of an active user; needs identity.users (Task 3). */
    public UUID insertUser(UUID tenantId, String email) {
        return inTx(null, null, c -> Sql.one(c, UUID.class,
                "insert into identity.users (tenant_id, email, status) values (?, ?, 'active') returning id",
                tenantId, email));
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }
}
