package com.rbac_platform.lib.db;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Database beans every service gets by having lib-db on the classpath; they need Boot's JdbcClient and TransactionTemplate. */
@EnableConfigurationProperties(DbProperties.class)
@AutoConfiguration(after = {JdbcClientAutoConfiguration.class, TransactionAutoConfiguration.class})
public class DbAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    TenantScope tenantScope(JdbcClient jdbc, TransactionTemplate transactions) {
        return new TenantScope(jdbc, transactions);
    }

    @Bean
    @ConditionalOnMissingBean
    DatabaseConnectionCheck databaseConnectionCheck(JdbcClient jdbc, DbProperties properties) {
        return new DatabaseConnectionCheck(jdbc, properties.expectedRole());
    }
}
