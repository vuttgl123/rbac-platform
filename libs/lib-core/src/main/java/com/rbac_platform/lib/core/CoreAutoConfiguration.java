package com.rbac_platform.lib.core;

import com.rbac_platform.lib.core.error.RequestIdErrorAttributes;
import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;

/**
 * Beans every service gets by having lib-core on the classpath. Ordered before Boot's error configuration
 * so that its ErrorAttributes bean backs off in favour of ours. A service's own bean of any of these types
 * makes ours back off.
 */
@AutoConfiguration(before = ErrorMvcAutoConfiguration.class)
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
}
