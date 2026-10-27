package com.rbac_platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbac_platform.AppDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DatabaseConnectionCheckTest {

    private final MigratedDatabase db = AppDatabase.enableAppLogin();

    @Test
    void acceptsAppRoleAndReportsWhereItConnected() {
        DriverManagerDataSource app = new DriverManagerDataSource(db.jdbcUrl(), "rbac_app", "unused");
        DatabaseConnectionCheck.ConnectionInfo info = new DatabaseConnectionCheck(JdbcClient.create(app)).verify();
        assertThat(info.user()).isEqualTo("rbac_app");
        assertThat(info.database()).isEqualTo("postgres");
        assertThat(info.serverVersion()).startsWith("17.");
    }

    @Test
    void refusesRolesThatBypassRowLevelSecurity() {
        assertThatThrownBy(() -> new DatabaseConnectionCheck(JdbcClient.create(db.dataSource())).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("postgres")
                .hasMessageContaining("bypasses row-level security");
    }
}
