package com.rbac_platform.db;

import static com.rbac_platform.db.Sql.one;
import static com.rbac_platform.db.Sql.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * scripts/db/scram_verifier.py produces a verifier PostgreSQL accepts as a stored password: a role whose password
 * is set from it can log in over scram-sha-256 with the original password, and not with another one.
 * Uses its own server because the shared embedded one trusts every local connection.
 */
class ScramVerifierTest {

    private static final Path SCRIPT = Path.of("scripts/db/scram_verifier.py");
    private static final String HBA = """
            local all all trust
            host all postgres 127.0.0.1/32 trust
            host all postgres ::1/128 trust
            host all all 127.0.0.1/32 scram-sha-256
            host all all ::1/128 scram-sha-256
            """;

    private static boolean python3Available() {
        try {
            Process p = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String verifier(String stdin) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("python3", SCRIPT.toString()).start();
        try (OutputStream in = p.getOutputStream()) {
            in.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.exitValue()).as("scram_verifier.py exit code, stderr: %s", err).isZero();
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    }

    @Test
    void postgresAcceptsLoginWithPasswordSetFromVerifier() throws Exception {
        assumeTrue(python3Available(), "python3 is required to run scripts/db/scram_verifier.py");
        assertThat(SCRIPT).exists();

        String password = "Corr3ct-horse:battery$staple";
        String v = verifier(password + "\n"); // a trailing newline from echo/heredoc must be dropped
        assertThat(v).matches("SCRAM-SHA-256\\$4096:[A-Za-z0-9+/]{22}==\\$[A-Za-z0-9+/]{43}=:[A-Za-z0-9+/]{43}=");
        assertThat(verifier(password)).as("fresh random salt per run").isNotEqualTo(v);

        try (EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setDataDirectoryCustomizer(dir -> {
                    try {
                        Files.writeString(dir.toPath().resolve("pg_hba.conf"), HBA);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .start()) {
            try (Connection su = pg.getPostgresDatabase().getConnection(); Statement s = su.createStatement()) {
                s.execute("CREATE ROLE scram_probe LOGIN PASSWORD '" + v + "'");
                // Stored verbatim, i.e. recognised as a verifier rather than hashed again as a password.
                assertThat(one(su, String.class, "select rolpassword from pg_authid where rolname = 'scram_probe'"))
                        .isEqualTo(v);
            }

            String url = pg.getJdbcUrl("scram_probe", "postgres");
            try (Connection c = DriverManager.getConnection(url, "scram_probe", password)) {
                assertThat(one(c, String.class, "select current_user")).isEqualTo("scram_probe");
                // system_user is "<auth method>:<authenticated identity>"
                assertThat(one(c, String.class, "select system_user")).isEqualTo("scram-sha-256:scram_probe");
            }
            // The server really checks the password: a wrong one is refused.
            assertThat(sqlState(() -> DriverManager.getConnection(url, "scram_probe", password + "x").close()))
                    .isEqualTo("28P01");
        }
    }
}
