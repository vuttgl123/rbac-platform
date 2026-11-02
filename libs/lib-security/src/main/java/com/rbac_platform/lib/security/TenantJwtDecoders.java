package com.rbac_platform.lib.security;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.rbac_platform.lib.core.http.HttpClientProperties;
import com.rbac_platform.lib.core.http.RequestIdForwardingInterceptor;
import java.net.URI;
import java.net.http.HttpClient;
import java.text.ParseException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/**
 * One {@link JwtDecoder} per tenant issuer. Only issuers of the exact form {@code {issuerPrefix}{slug}} are
 * trusted; anything else gets no decoder, so a token naming a foreign issuer is rejected before any network call.
 *
 * <p>A decoder is created cheaply and discovers its issuer lazily, on the first token it sees: it fetches
 * {@code {issuer}/.well-known/openid-configuration} itself (one request, not Spring's several fallbacks), checks
 * the advertised issuer, then verifies ES256 signatures against the advertised {@code jwks_uri}. Every call to
 * identity goes through the given {@link RestOperations}, whose timeouts bound how long a request can wait.
 *
 * <p>Any failure to reach or use identity surfaces as {@link BadJwtException}, i.e. 401: Spring would turn other
 * {@link JwtException}s into a rethrown {@code AuthenticationServiceException} (500). After a failed discovery the
 * decoder fails fast for {@value #FAILURE_BACKOFF_SECONDS} seconds instead of making every request wait for a
 * dead or unknown issuer again.
 */
public class TenantJwtDecoders {

    private static final Logger log = LoggerFactory.getLogger(TenantJwtDecoders.class);

    static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    static final int MAX_DECODERS = 1000;
    static final long FAILURE_BACKOFF_SECONDS = 5;

    private final String issuerPrefix;
    private final String audience;
    private final TenantStatusCache tenants;
    private final RestOperations rest;
    private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();

    public TenantJwtDecoders(String issuerPrefix, String audience, TenantStatusCache tenants, RestOperations rest) {
        this.issuerPrefix = issuerPrefix;
        this.audience = audience;
        this.tenants = tenants;
        this.rest = rest;
    }

    /** A client for identity's discovery and JWKS endpoints with the service-call timeouts and request id forwarding. */
    public static RestOperations restOperations(HttpClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        RestTemplate rest = new RestTemplate(factory);
        rest.setInterceptors(List.of(new RequestIdForwardingInterceptor()));
        return rest;
    }

    /** The decoder for this issuer, or null when the issuer is not {@code issuerPrefix} followed by one slug. */
    public JwtDecoder decoderFor(String issuer) {
        if (issuer == null || !issuer.startsWith(issuerPrefix)
                || !SLUG.matcher(issuer.substring(issuerPrefix.length())).matches()) {
            return null;
        }
        JwtDecoder decoder = decoders.get(issuer);
        if (decoder != null) {
            return decoder;
        }
        if (decoders.size() >= MAX_DECODERS) {
            decoders.clear();
        }
        return decoders.computeIfAbsent(issuer, IssuerDecoder::new);
    }

    private final class IssuerDecoder implements JwtDecoder {

        private final String issuer;
        private volatile NimbusJwtDecoder delegate;
        private long retryAfterNanos;
        private boolean failed;

        IssuerDecoder(String issuer) {
            this.issuer = issuer;
        }

        @Override
        public Jwt decode(String token) {
            NimbusJwtDecoder decoder = delegate();
            try {
                return decoder.decode(token);
            } catch (BadJwtException e) {
                throw e;
            } catch (JwtException e) {
                log.warn("Cannot verify tokens of issuer {}: {}", issuer, e.getMessage());
                throw new BadJwtException("Signing keys of the issuer are unavailable", e);
            }
        }

        private NimbusJwtDecoder delegate() {
            NimbusJwtDecoder decoder = delegate;
            if (decoder != null) {
                return decoder;
            }
            synchronized (this) {
                if (delegate != null) {
                    return delegate;
                }
                if (failed && System.nanoTime() - retryAfterNanos < 0) {
                    throw new BadJwtException("Issuer is unavailable");
                }
                try {
                    delegate = discover();
                    return delegate;
                } catch (RuntimeException e) {
                    failed = true;
                    retryAfterNanos = System.nanoTime() + Duration.ofSeconds(FAILURE_BACKOFF_SECONDS).toNanos();
                    log.warn("Discovery of issuer {} failed: {}", issuer, e.toString());
                    throw new BadJwtException("Issuer is unavailable", e);
                }
            }
        }

        private NimbusJwtDecoder discover() {
            RequestEntity<Void> request = RequestEntity.get(URI.create(issuer + "/.well-known/openid-configuration"))
                    .accept(MediaType.APPLICATION_JSON)
                    .build();
            String body = rest.exchange(request, String.class).getBody();
            Map<String, Object> configuration;
            try {
                configuration = JSONObjectUtils.parse(body == null ? "" : body);
            } catch (ParseException e) {
                throw new IllegalStateException("discovery document is not a JSON object", e);
            }
            if (!issuer.equals(configuration.get("issuer"))) {
                throw new IllegalStateException("discovery document names issuer " + configuration.get("issuer"));
            }
            if (!(configuration.get("jwks_uri") instanceof String jwksUri) || jwksUri.isBlank()) {
                throw new IllegalStateException("discovery document has no jwks_uri");
            }
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                    .jwsAlgorithm(SignatureAlgorithm.ES256)
                    .restOperations(rest)
                    .build();
            decoder.setJwtValidator(TokenValidators.accessToken(issuer, audience, tenants));
            return decoder;
        }
    }
}
