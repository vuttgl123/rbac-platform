package com.rbac_platform.lib.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code rbac.security.*}: how a service trusts access tokens. Every tenant has its own issuer
 * {@code {issuerPrefix}{slug}}, so the prefix (identity's public URL plus {@code /t/}) is the only trust anchor
 * a service is configured with.
 *
 * @param issuerPrefix   required, ends with {@code /t/}; a token's {@code iss} must be this plus one tenant slug
 * @param audience       the {@code aud} every access token must carry
 * @param publicPaths    request patterns reachable without a token, besides the health check and {@code /error}
 * @param tenantCacheTtl how long a tenant's status is cached; a suspended tenant's tokens stop working within it
 * @param internalToken  shared secret for {@code /internal/**} calls between services (required, at least 32 characters; see {@link InternalTokenFilter})
 * @param authzUrl       base URL of authz-service, for services that ask it for decisions
 */
@ConfigurationProperties("rbac.security")
public record SecurityProperties(
        String issuerPrefix,
        @DefaultValue("rbac-api") String audience,
        @DefaultValue List<String> publicPaths,
        @DefaultValue("30s") Duration tenantCacheTtl,
        String internalToken,
        String authzUrl) {

    public SecurityProperties {
        if (issuerPrefix == null || issuerPrefix.isBlank() || !issuerPrefix.endsWith("/t/")) {
            throw new IllegalArgumentException(
                    "rbac.security.issuer-prefix is required and must end with /t/ (identity's public URL + /t/)");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("rbac.security.audience must not be blank");
        }
        if (tenantCacheTtl == null || tenantCacheTtl.isNegative() || tenantCacheTtl.isZero()) {
            throw new IllegalArgumentException("rbac.security.tenant-cache-ttl must be positive");
        }
        if (internalToken == null || internalToken.length() < 32) {
            throw new IllegalArgumentException(
                    "rbac.security.internal-token is required and must be at least 32 characters");
        }
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
    }

    /** Keeps the internal token out of logs and actuator output. */
    @Override
    public String toString() {
        return "SecurityProperties[issuerPrefix=" + issuerPrefix + ", audience=" + audience + ", publicPaths="
                + publicPaths + ", tenantCacheTtl=" + tenantCacheTtl + ", internalToken="
                + (internalToken == null ? "null" : "***") + ", authzUrl=" + authzUrl + "]";
    }
}
