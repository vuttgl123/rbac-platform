package com.rbac_platform.lib.core;

import com.rbac_platform.lib.core.error.ApiExceptionHandler;
import com.rbac_platform.lib.core.error.ProblemErrorController;
import com.rbac_platform.lib.core.error.ProblemWriter;
import com.rbac_platform.lib.core.event.EventDispatcher;
import com.rbac_platform.lib.core.event.EventHandler;
import com.rbac_platform.lib.core.event.EventHandlerRegistry;
import com.rbac_platform.lib.core.event.EventProperties;
import com.rbac_platform.lib.core.http.HttpClientProperties;
import com.rbac_platform.lib.core.http.ServiceRestClients;
import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Beans every service gets by having lib-core on the classpath. Ordered before Boot's error configuration
 * so that its BasicErrorController backs off in favour of {@link ProblemErrorController}: errors raised outside
 * Spring MVC are problem+json too. A service's own bean of any of these types makes ours back off.
 */
@AutoConfiguration(before = ErrorMvcAutoConfiguration.class)
@EnableConfigurationProperties({EventProperties.class, HttpClientProperties.class})
public class CoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RequestLoggingFilter requestLoggingFilter() {
        return new RequestLoggingFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemWriter problemWriter(ObjectProvider<JsonMapper> json) {
        return new ProblemWriter(json.getIfAvailable(() -> JsonMapper.builder().build()));
    }

    @Bean
    @ConditionalOnMissingBean(ErrorController.class)
    ProblemErrorController problemErrorController(ProblemWriter problems) {
        return new ProblemErrorController(problems);
    }

    @Bean
    @ConditionalOnMissingBean
    ApiExceptionHandler apiExceptionHandler(ProblemWriter problems) {
        return new ApiExceptionHandler(problems);
    }

    @Bean
    @ConditionalOnMissingBean
    EventHandlerRegistry eventHandlerRegistry(ObjectProvider<EventHandler> handlers) {
        return new EventHandlerRegistry(handlers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    EventDispatcher eventDispatcher(EventHandlerRegistry registry, EventProperties properties) {
        return new EventDispatcher(registry, properties.unhandled());
    }

    @Bean
    @ConditionalOnMissingBean
    ServiceRestClients serviceRestClients(HttpClientProperties properties) {
        return new ServiceRestClients(properties);
    }
}
