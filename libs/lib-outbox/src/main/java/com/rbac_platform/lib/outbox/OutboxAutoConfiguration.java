package com.rbac_platform.lib.outbox;

import com.rbac_platform.lib.db.DbAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** The outbox of a service that sets rbac.outbox.schema; a service without that property gets nothing. */
@AutoConfiguration(after = DbAutoConfiguration.class, afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@ConditionalOnProperty("rbac.outbox.schema")
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxWakeup outboxWakeup() {
        return new OutboxWakeup();
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxWriter outboxWriter(JdbcClient jdbc, JsonMapper json, OutboxWakeup wakeup, OutboxProperties properties) {
        return new OutboxWriter(jdbc, json, wakeup, properties.schema());
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxStore outboxStore(JdbcClient jdbc, JsonMapper json, OutboxProperties properties) {
        return new OutboxStore(jdbc, json, properties.schema());
    }
}
