package com.rbac_platform.lib.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.testsupport.AppDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** A service that defines its own beans of these types replaces lib-db's instead of colliding with them. */
@SpringBootTest(classes = DbTestApplication.class)
@Import(DbAutoConfigurationTest.ServiceBeans.class)
class DbAutoConfigurationTest {

    @TestConfiguration
    static class ServiceBeans {

        @Bean
        TenantScope serviceTenantScope(JdbcClient jdbc, TransactionTemplate transactions) {
            return new TenantScope(jdbc, transactions);
        }

        @Bean
        DatabaseConnectionCheck serviceConnectionCheck(JdbcClient jdbc) {
            return new DatabaseConnectionCheck(jdbc, "rbac_app");
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppDatabase.register(registry, "rbac_app");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void backsOffWhenTheServiceDefinesItsOwnBeans() {
        assertThat(context.getBeansOfType(TenantScope.class)).containsOnlyKeys("serviceTenantScope");
        assertThat(context.getBeansOfType(DatabaseConnectionCheck.class)).containsOnlyKeys("serviceConnectionCheck");
    }
}
