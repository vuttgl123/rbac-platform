package com.rbac_platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbac_platform.AppDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class TenantScopeTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppDatabase.register(registry);
    }

    private final MigratedDatabase db = MigratedDatabase.get();

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TenantScope tenantScope;

    @Autowired
    private TransactionTemplate transactions;

    private long visibleUsers() {
        return jdbc.sql("select count(*) from identity.users").query(Long.class).single();
    }

    @Test
    void applicationConnectsAsAppRoleAndSeesNoTenantRowsOutsideAScope() {
        UUID t = db.insertTenant();
        db.insertUser(t, "outside@scope.test");
        assertThat(jdbc.sql("select current_user").query(String.class).single()).isEqualTo("rbac_app");
        assertThat(visibleUsers()).isZero();
    }

    @Test
    void scopeShowsOnlyThatTenantsRows() {
        UUID a = db.insertTenant();
        UUID b = db.insertTenant();
        db.insertUser(a, "alice@a.test");
        db.insertUser(b, "bob@b.test");

        String seen = tenantScope.call(a, () ->
                jdbc.sql("select string_agg(email::text, ',') from identity.users").query(String.class).single());

        assertThat(seen).isEqualTo("alice@a.test");
        assertThat(tenantScope.call(b, this::visibleUsers)).isEqualTo(1);
        // The tenant setting is transaction-local: nothing leaks to the next use of the pooled connection.
        assertThat(visibleUsers()).isZero();
    }

    @Test
    void scopeRefusesToRunInsideAnotherTransaction() {
        UUID a = db.insertTenant();
        assertThatThrownBy(() -> transactions.executeWithoutResult(s -> tenantScope.call(a, this::visibleUsers)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in a transaction");
    }

    @Test
    void scopeRequiresATenant() {
        assertThatThrownBy(() -> tenantScope.call(null, this::visibleUsers))
                .isInstanceOf(NullPointerException.class);
    }
}
