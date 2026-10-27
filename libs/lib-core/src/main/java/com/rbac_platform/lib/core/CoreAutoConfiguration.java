package com.rbac_platform.lib.core;

import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Beans every service gets by having lib-core on the classpath. */
@AutoConfiguration
public class CoreAutoConfiguration {

    @Bean
    RequestLoggingFilter requestLoggingFilter() {
        return new RequestLoggingFilter();
    }
}
