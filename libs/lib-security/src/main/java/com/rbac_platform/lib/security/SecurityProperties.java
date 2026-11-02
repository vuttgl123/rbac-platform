package com.rbac_platform.lib.security;

import com.rbac_platform.lib.core.config.Secrets;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code rbac.security.*}: how a service trusts access tokens. Every tenant has its own issuer
 * {@code {issuerPrefix}{slug}}, so the prefix (identity's public URL plus {@code /t/}) is the only trust anchor
 * a service is configured with.
 *
 * @param issuerPrefix   required absolute http(s) URL ending with {@code /t/}; a token's {@code iss} must be this
 *                       plus one tenant slug
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
        requireIssuerPrefix(issuerPrefix);
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("rbac.security.audience must not be blank");
        }
        if (tenantCacheTtl == null || tenantCacheTtl.isNegative() || tenantCacheTtl.isZero()) {
            throw new IllegalArgumentException("rbac.security.tenant-cache-ttl must be positive");
        }
        Secrets.requireSecret("rbac.security.internal-token", internalToken);
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
    }

    /**
     * The prefix is compared as text against every token's {@code iss}, so it must be exactly the URL identity
     * signs with: absolute http(s) with a host, ending with {@code /t/}, no query or fragment, and no empty,
     * {@code .} or {@code ..} path segment that a client or proxy would normalise away.
     */
    private static void requireIssuerPrefix(String prefix) {
        String rule = "rbac.security.issuer-prefix is required and must be an absolute http(s) URL with a host, "
                + "ending with /t/, without empty, '.' or '..' path segments (identity's public URL + /t/): ";
        if (prefix == null || !prefix.endsWith("/t/")) {
            throw new IllegalArgumentException(rule + prefix);
        }
        URI uri;
        try {
            uri = new URI(prefix);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(rule + prefix, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(rule + prefix);
        }
        String path = uri.getRawPath();
        // The path starts with "/" and ends with "/t/"; drop both ends and every remaining segment must be real.
        for (String segment : path.substring(1, path.length() - 1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(rule + prefix);
            }
        }
    }

    /** Keeps the internal token out of logs and actuator output. */
    @Override
    public String toString() {
        return "SecurityProperties[issuerPrefix=" + issuerPrefix + ", audience=" + audience + ", publicPaths="
                + publicPaths + ", tenantCacheTtl=" + tenantCacheTtl + ", internalToken="
                + (internalToken == null ? "null" : "***") + ", authzUrl=" + authzUrl + "]";
    }
}
