package com.rbac_platform.lib.outbox;

import com.rbac_platform.lib.core.event.EventDispatcher;
import com.rbac_platform.lib.db.DbAutoConfiguration;
import com.rbac_platform.lib.outbox.publish.InProcessOutboxPublisher;
import com.rbac_platform.lib.outbox.publish.LoggingOutboxPublisher;
import com.rbac_platform.lib.outbox.publish.OutboxPublisher;
import com.rbac_platform.lib.outbox.relay.MdcOutboxInterceptor;
import com.rbac_platform.lib.outbox.relay.OutboxInterceptor;
import com.rbac_platform.lib.outbox.relay.OutboxRelay;
import com.rbac_platform.lib.outbox.relay.TimingOutboxInterceptor;
import com.rbac_platform.lib.outbox.retry.ExponentialBackoffRetryPolicy;
import com.rbac_platform.lib.outbox.retry.RetryPolicy;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox of a service that sets rbac.outbox.schema; a service without that property gets nothing. Each strategy
 * (publisher, retry policy) backs off when the service declares its own bean; interceptors add up.
 */
@AutoConfiguration(after = DbAutoConfiguration.class, afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@ConditionalOnProperty("rbac.outbox.schema")
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    private static final String PUBLISHER = "rbac.outbox.publisher";
    private static final String SHUTDOWN_PHASE_TIMEOUT = "spring.lifecycle.timeout-per-shutdown-phase";
    private static final Duration DEFAULT_SHUTDOWN_PHASE_TIMEOUT = Duration.ofSeconds(30);

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

    @Bean
    @ConditionalOnMissingBean(OutboxPublisher.class)
    @ConditionalOnProperty(name = PUBLISHER, havingValue = "log", matchIfMissing = true)
    LoggingOutboxPublisher loggingOutboxPublisher() {
        return new LoggingOutboxPublisher();
    }

    @Bean
    @ConditionalOnMissingBean(OutboxPublisher.class)
    @ConditionalOnProperty(name = PUBLISHER, havingValue = "in-process")
    InProcessOutboxPublisher inProcessOutboxPublisher(EventDispatcher dispatcher) {
        return new InProcessOutboxPublisher(dispatcher);
    }

    @Bean
    @ConditionalOnMissingBean(RetryPolicy.class)
    ExponentialBackoffRetryPolicy outboxRetryPolicy(OutboxProperties properties) {
        // Thread-safe without contention: every draw goes to the calling thread's own generator.
        RandomGenerator random = () -> ThreadLocalRandom.current().nextLong();
        return new ExponentialBackoffRetryPolicy(properties.backoff().initial(), properties.backoff().max(),
                properties.maxAttempts(), random);
    }

    @Bean
    @ConditionalOnMissingBean
    MdcOutboxInterceptor mdcOutboxInterceptor() {
        return new MdcOutboxInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    TimingOutboxInterceptor timingOutboxInterceptor() {
        return new TimingOutboxInterceptor();
    }

    /**
     * The publisher is whichever bean exists: one of the two above, picked by name, or a service's (or another lib's)
     * own. A name nobody provides fails startup here rather than at the first event.
     */
    @Bean
    @ConditionalOnMissingBean
    OutboxRelay outboxRelay(OutboxStore store, OutboxWakeup wakeup, ObjectProvider<OutboxPublisher> publishers,
                            RetryPolicy retryPolicy, ObjectProvider<OutboxInterceptor> interceptors,
                            TransactionTemplate transactions, OutboxProperties properties, Environment environment) {
        OutboxPublisher publisher = publishers.getIfAvailable(() -> {
            throw new IllegalStateException("No OutboxPublisher for " + PUBLISHER + "='" + properties.publisher() + "'");
        });
        return new OutboxRelay(store, wakeup, publisher, retryPolicy, interceptors.orderedStream().toList(),
                transactions, properties, shutdownPhaseTimeout(environment));
    }

    /** Boot's spring.lifecycle.timeout-per-shutdown-phase, with Boot's own default when it is not set. */
    static Duration shutdownPhaseTimeout(Environment environment) {
        return Binder.get(environment).bind(SHUTDOWN_PHASE_TIMEOUT, Duration.class).orElse(DEFAULT_SHUTDOWN_PHASE_TIMEOUT);
    }
}
