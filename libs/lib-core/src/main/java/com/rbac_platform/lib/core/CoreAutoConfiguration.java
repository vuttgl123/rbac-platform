package com.rbac_platform.lib.core;

import com.rbac_platform.lib.core.error.RequestIdErrorAttributes;
import com.rbac_platform.lib.core.event.EventDispatcher;
import com.rbac_platform.lib.core.event.EventHandler;
import com.rbac_platform.lib.core.event.EventHandlerRegistry;
import com.rbac_platform.lib.core.event.EventProperties;
import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;

/**
 * Beans every service gets by having lib-core on the classpath. Ordered before Boot's error configuration
 * so that its ErrorAttributes bean backs off in favour of ours. A service's own bean of any of these types
 * makes ours back off.
 */
@AutoConfiguration(before = ErrorMvcAutoConfiguration.class)
@EnableConfigurationProperties(EventProperties.class)
public class CoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RequestLoggingFilter requestLoggingFilter() {
        return new RequestLoggingFilter();
    }

    @Bean
    @ConditionalOnMissingBean(ErrorAttributes.class)
    RequestIdErrorAttributes errorAttributes() {
        return new RequestIdErrorAttributes();
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
}
