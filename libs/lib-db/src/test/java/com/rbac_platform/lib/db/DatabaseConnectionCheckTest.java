package com.rbac_platform.lib.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DatabaseConnectionCheckTest {

    private final MigratedDatabase db = AppDatabase.enableLogin("rbac_app");

    @Test
    void acceptsAppRoleAndReportsWhereItConnected() {
        DriverManagerDataSource app = new DriverManagerDataSource(db.jdbcUrl(), "rbac_app", "unused");
        DatabaseConnectionCheck.ConnectionInfo info = new DatabaseConnectionCheck(JdbcClient.create(app), "rbac_app").verify();
        assertThat(info.user()).isEqualTo("rbac_app");
        assertThat(info.database()).isEqualTo("postgres");
        assertThat(info.serverVersion()).startsWith("17.");
    }

    @Test
    void refusesRolesThatBypassRowLevelSecurity() {
        assertThatThrownBy(() -> new DatabaseConnectionCheck(JdbcClient.create(db.dataSource()), "postgres").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("postgres")
                .hasMessageContaining("bypasses row-level security")
                .hasMessageContaining("without SUPERUSER or BYPASSRLS")
                .hasMessageNotContaining("instead");
    }

    @Test
    void refusesAConnectionAsAnotherRole() {
        DriverManagerDataSource app = new DriverManagerDataSource(db.jdbcUrl(), "rbac_app", "unused");
        assertThatThrownBy(() -> new DatabaseConnectionCheck(JdbcClient.create(app), "identity_app").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expects database role 'identity_app'")
                .hasMessageContaining("rbac_app");
    }

    @Test
    void requiresAnExpectedRole() {
        assertThatThrownBy(() -> new DatabaseConnectionCheck(JdbcClient.create(db.dataSource()), " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rbac.db.expected-role");
    }
}
