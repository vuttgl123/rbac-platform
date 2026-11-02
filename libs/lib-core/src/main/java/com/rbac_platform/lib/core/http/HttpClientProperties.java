package com.rbac_platform.lib.core.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code rbac.http.*}: timeouts for service-to-service calls. Short by default so a slow dependency fails
 * fast instead of tying up request threads.
 */
@ConfigurationProperties("rbac.http")
public record HttpClientProperties(
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("2s") Duration readTimeout) {

    public HttpClientProperties {
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("rbac.http.connect-timeout must be positive");
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("rbac.http.read-timeout must be positive");
        }
    }
}
