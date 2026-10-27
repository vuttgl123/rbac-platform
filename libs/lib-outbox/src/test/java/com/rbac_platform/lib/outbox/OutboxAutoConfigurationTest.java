package com.rbac_platform.lib.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.rbac_platform.lib.core.event.EventDispatcher;
import com.rbac_platform.lib.core.event.EventHandlerRegistry;
import com.rbac_platform.lib.core.event.UnhandledEventPolicy;
import com.rbac_platform.lib.outbox.publish.InProcessOutboxPublisher;
import com.rbac_platform.lib.outbox.publish.LoggingOutboxPublisher;
import com.rbac_platform.lib.outbox.publish.OutboxPublisher;
import com.rbac_platform.lib.outbox.relay.MdcOutboxInterceptor;
import com.rbac_platform.lib.outbox.relay.OutboxInterceptor;
import com.rbac_platform.lib.outbox.relay.OutboxRelay;
import com.rbac_platform.lib.outbox.relay.TimingOutboxInterceptor;
import com.rbac_platform.lib.outbox.retry.ExponentialBackoffRetryPolicy;
import com.rbac_platform.lib.outbox.retry.RetryDecision;
import com.rbac_platform.lib.outbox.retry.RetryPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class OutboxAutoConfigurationTest {

    /** The outbox auto-configuration over stand-ins for what lib-core and lib-db provide; the relay is not started. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(JsonMapper.class, JsonMapper::new)
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withBean(EventDispatcher.class,
                    () -> new EventDispatcher(new EventHandlerRegistry(List.of()), UnhandledEventPolicy.IGNORE))
            .withPropertyValues("rbac.outbox.schema=identity", "rbac.outbox.enabled=false");

    @Test
    void publisherIsChosenByConfiguration() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(OutboxPublisher.class);
            assertThat(context.getBean(OutboxPublisher.class)).isInstanceOf(LoggingOutboxPublisher.class);
            assertThat(context).hasSingleBean(OutboxRelay.class);
        });
        runner.withPropertyValues("rbac.outbox.publisher=log").run(context -> {
            assertThat(context).hasSingleBean(OutboxPublisher.class);
            assertThat(context.getBean(OutboxPublisher.class)).isInstanceOf(LoggingOutboxPublisher.class);
        });
        runner.withPropertyValues("rbac.outbox.publisher=in-process").run(context -> {
            assertThat(context).hasSingleBean(OutboxPublisher.class);
            assertThat(context.getBean(OutboxPublisher.class)).isInstanceOf(InProcessOutboxPublisher.class);
        });
    }

    @Test
    void unknownPublisherFailsStartup() {
        runner.withPropertyValues("rbac.outbox.publisher=kafka").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure()
                    .hasStackTraceContaining("No OutboxPublisher for rbac.outbox.publisher='kafka'");
        });
    }

    @Test
    void customPublisherAndRetryPolicyReplaceTheDefaults() {
        OutboxPublisher custom = event -> {
        };
        RetryPolicy policy = (record, failure, now) -> new RetryDecision.Bury("never retry");
        // As lib-kafka would: its own publisher, under a publisher name the outbox does not know itself.
        runner.withPropertyValues("rbac.outbox.publisher=kafka")
                .withBean(OutboxPublisher.class, () -> custom)
                .withBean(RetryPolicy.class, () -> policy)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OutboxPublisher.class)).isSameAs(custom);
                    assertThat(context.getBean(RetryPolicy.class)).isSameAs(policy);
                    assertThat(context).doesNotHaveBean(LoggingOutboxPublisher.class)
                            .doesNotHaveBean(InProcessOutboxPublisher.class)
                            .doesNotHaveBean(ExponentialBackoffRetryPolicy.class)
                            .hasSingleBean(OutboxRelay.class);
                });
    }

    @Test
    void builtInInterceptorsAreAlwaysRegisteredAlongsideTheServicesOwn() {
        OutboxInterceptor metrics = new OutboxInterceptor() {
        };
        runner.run(context -> {
            assertThat(context).hasSingleBean(MdcOutboxInterceptor.class).hasSingleBean(TimingOutboxInterceptor.class);
            assertThat(context.getBeansOfType(OutboxInterceptor.class)).hasSize(2);
            assertThat(context).hasSingleBean(ExponentialBackoffRetryPolicy.class);
        });
        runner.withBean("metricsInterceptor", OutboxInterceptor.class, () -> metrics).run(context -> {
            assertThat(context).hasSingleBean(MdcOutboxInterceptor.class).hasSingleBean(TimingOutboxInterceptor.class);
            assertThat(context.getBeansOfType(OutboxInterceptor.class)).hasSize(3).containsValue(metrics);
        });
    }

    @Test
    void relayStartsOnlyWhenEnabled() {
        runner.run(context -> {
            OutboxRelay relay = context.getBean(OutboxRelay.class);
            assertThat(relay.isAutoStartup()).isFalse();
            assertThat(relay.isRunning()).isFalse();
        });
        // A store with nothing to claim, so the started relay idles.
        runner.withPropertyValues("rbac.outbox.enabled=true")
                .withBean(OutboxStore.class, () -> mock(OutboxStore.class))
                .run(context -> {
                    OutboxRelay relay = context.getBean(OutboxRelay.class);
                    assertThat(relay.isAutoStartup()).isTrue();
                    assertThat(relay.isRunning()).isTrue();
                    relay.stop();
                    assertThat(relay.isRunning()).isFalse();
                });
    }

    @Test
    void nothingIsConfiguredWithoutASchema() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(OutboxRelay.class)
                        .doesNotHaveBean(OutboxPublisher.class)
                        .doesNotHaveBean(RetryPolicy.class)
                        .doesNotHaveBean(OutboxInterceptor.class));
    }
}
