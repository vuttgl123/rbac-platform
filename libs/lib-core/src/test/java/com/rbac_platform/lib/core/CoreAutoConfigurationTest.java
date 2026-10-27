package com.rbac_platform.lib.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

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
}
