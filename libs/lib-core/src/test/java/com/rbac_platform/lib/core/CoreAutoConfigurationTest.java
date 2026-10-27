package com.rbac_platform.lib.core;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbac_platform.lib.core.event.EventDispatcher;
import com.rbac_platform.lib.core.event.EventEnvelope;
import com.rbac_platform.lib.core.event.EventHandler;
import com.rbac_platform.lib.core.event.EventHandlerRegistry;
import com.rbac_platform.lib.core.event.UnhandledEventException;
import com.rbac_platform.lib.core.event.UnhandledEventPolicy;
import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.node.JsonNodeFactory;

/** A service that defines its own beans of these types replaces lib-core's instead of colliding with them. */
@SpringBootTest(classes = CoreTestApplication.class)
@Import(CoreAutoConfigurationTest.ServiceBeans.class)
class CoreAutoConfigurationTest {

    @TestConfiguration
    static class ServiceBeans {

        @Bean
        ErrorAttributes serviceErrorAttributes() {
            return new DefaultErrorAttributes();
        }

        @Bean
        RequestLoggingFilter serviceRequestLoggingFilter() {
            return new RequestLoggingFilter();
        }
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void backsOffWhenTheServiceDefinesItsOwnErrorAttributes() {
        assertThat(context.getBeansOfType(ErrorAttributes.class)).containsOnlyKeys("serviceErrorAttributes");
    }

    @Test
    void backsOffWhenTheServiceDefinesItsOwnRequestLoggingFilter() {
        assertThat(context.getBeansOfType(RequestLoggingFilter.class)).containsOnlyKeys("serviceRequestLoggingFilter");
    }

    @Test
    void eventBeansAreAutoConfiguredAndBackOff() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CoreAutoConfiguration.class))
                .withBean("first", EventHandler.class, () -> eventHandler("a"))
                .withBean("second", EventHandler.class, () -> eventHandler("b"));

        runner.run(ctx -> {
            assertThat(ctx.getBean(EventHandlerRegistry.class).handlersFor("a")).hasSize(1);
            assertThat(ctx.getBean(EventHandlerRegistry.class).handlersFor("b")).hasSize(1);
            // Default policy is IGNORE: an unknown type is not an error.
            ctx.getBean(EventDispatcher.class).dispatch(event("unknown"));
        });
        runner.withPropertyValues("rbac.events.unhandled=fail").run(ctx ->
                assertThatThrownBy(() -> ctx.getBean(EventDispatcher.class).dispatch(event("unknown")))
                        .isInstanceOf(UnhandledEventException.class));
        EventDispatcher own = new EventDispatcher(new EventHandlerRegistry(List.of()),
                UnhandledEventPolicy.IGNORE);
        runner.withBean("ownDispatcher", EventDispatcher.class, () -> own).run(ctx ->
                assertThat(ctx.getBeansOfType(EventDispatcher.class)).containsOnlyKeys("ownDispatcher"));
    }

    private static EventHandler eventHandler(String type) {
        return new EventHandler() {
            @Override
            public Set<String> eventTypes() {
                return Set.of(type);
            }

            @Override
            public void handle(EventEnvelope event) {
            }
        };
    }

    private static EventEnvelope event(String type) {
        return new EventEnvelope(UUID.randomUUID(), UUID.randomUUID(), "user", "42", type,
                JsonNodeFactory.instance.objectNode(), Map.of(), Instant.now());
    }
}
