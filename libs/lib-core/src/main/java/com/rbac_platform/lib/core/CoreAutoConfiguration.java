package com.rbac_platform.lib.core;

import com.rbac_platform.lib.core.error.RequestIdErrorAttributes;
import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Beans every service gets by having lib-core on the classpath. Ordered before Boot's error configuration
 * so that its ErrorAttributes bean backs off in favour of ours.
 */
@AutoConfiguration(before = ErrorMvcAutoConfiguration.class)
public class CoreAutoConfiguration {

    @Bean
    RequestLoggingFilter requestLoggingFilter() {
        return new RequestLoggingFilter();
    }

    @Bean
    RequestIdErrorAttributes errorAttributes() {
        return new RequestIdErrorAttributes();
    }
}
