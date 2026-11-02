package com.rbac_platform.lib.security;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jose.util.Resource;
import com.rbac_platform.lib.core.http.HttpClientProperties;
import com.rbac_platform.lib.core.http.RequestIdForwardingInterceptor;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.text.ParseException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/**
 * Decoders for the tenant issuers {@code {issuerPrefix}{slug}}. Tokens reach this class before their signature
 * is checked, so the work an unauthenticated token can cause is bounded:
 *
 * <ul>
 *   <li>An issuer not of that exact form gets no decoder. Neither does a slug that is not an active tenant in
 *       the local database ({@link TenantStatusCache}, cached including misses). Both are rejected without any
 *       call to identity.</li>
 *   <li>A tenant's decoder is created on its first token. It fetches {@code {issuer}/.well-known/openid-configuration}
 *       itself: one request, where Spring's issuer-location support would try several fallback URLs. It checks
 *       the advertised issuer and verifies ES256 signatures against the advertised {@code jwks_uri}.
 *       Concurrent first tokens share one discovery.</li>
 *   <li>Only decoders whose discovery succeeded are kept, in a least-recently-used map. A flood cannot evict the
 *       tenants in use, and failed issuers take no room there.</li>
 *   <li>A failed discovery is remembered in a separate small map. For {@value #FAILURE_BACKOFF_SECONDS} seconds
 *       that issuer's tokens fail at once, with no request to identity and no log line.</li>
 *   <li>Keys are cached. A token with an unknown {@code kid} may refresh the JWK set, but at most twice per
 *       {@value #JWKS_MIN_REFRESH_SECONDS} seconds per issuer. Requests waiting behind a refresh give up after
 *       half the read timeout, so an unreachable JWKS cannot queue requests.</li>
 *   <li>Every call to identity uses the service-call timeouts.</li>
 * </ul>
 *
 * <p>Any failure to reach or use identity surfaces as {@link BadJwtException}, which means 401. Spring turns
 * any other {@link JwtException} into a rethrown {@code AuthenticationServiceException}, which means 500.
 */
public class TenantJwtDecoders {

    private static final Logger log = LoggerFactory.getLogger(TenantJwtDecoders.class);

    static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    static final int MAX_DECODERS = 1000;
    static final int MAX_FAILURES = 1000;
    static final long FAILURE_BACKOFF_SECONDS = 5;
    static final long JWKS_MIN_REFRESH_SECONDS = 30;

    private static final MediaType JWK_SET_JSON = new MediaType("application", "jwk-set+json");

    private final String issuerPrefix;
    private final String audience;
    private final TenantStatusCache tenants;
    private final RestOperations rest;
    private final long jwksRefreshWaitMillis;
    private final Duration discoveryWait;
    private final long backoffNanos = TimeUnit.SECONDS.toNanos(FAILURE_BACKOFF_SECONDS);

    // Both guarded by their own monitor; access order makes them LRU.
    private final Map<String, NimbusJwtDecoder> decoders = lru(MAX_DECODERS);
    // issuer -> System.nanoTime() before which discovery is not retried
    private final Map<String, Long> discoveryBackoff = lru(MAX_FAILURES);
    // issuer -> System.nanoTime() before which another JWKS failure is not logged
    private final Map<String, Long> jwksWarnings = lru(MAX_FAILURES);
    private final Map<String, CompletableFuture<NimbusJwtDecoder>> discovering = new ConcurrentHashMap<>();

    public TenantJwtDecoders(String issuerPrefix, String audience, TenantStatusCache tenants, HttpClientProperties http) {
        this.issuerPrefix = issuerPrefix;
        this.audience = audience;
        this.tenants = tenants;
        this.rest = restOperations(http);
        this.jwksRefreshWaitMillis = Math.max(1, http.readTimeout().toMillis() / 2);
        this.discoveryWait = http.connectTimeout().plus(http.readTimeout()).plusSeconds(1);
    }

    /** A client for identity's discovery and JWKS endpoints with the service-call timeouts and request id forwarding. */
    static RestOperations restOperations(HttpClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        RestTemplate rest = new RestTemplate(factory);
        rest.setInterceptors(List.of(new RequestIdForwardingInterceptor()));
        return rest;
    }

    /**
     * The decoder for this issuer, or null when the issuer is not {@code issuerPrefix} followed by one slug, or
     * no active tenant has that slug. Cheap: discovery happens when the decoder first decodes a token.
     */
    public JwtDecoder decoderFor(String issuer) {
        if (issuer == null || !issuer.startsWith(issuerPrefix)) {
            return null;
        }
        String slug = issuer.substring(issuerPrefix.length());
        if (!SLUG.matcher(slug).matches() || tenants.activeTenant(slug) == null) {
            return null;
        }
        return token -> decode(issuer, slug, token);
    }

    private Jwt decode(String issuer, String slug, String token) {
        NimbusJwtDecoder decoder = decoder(issuer, slug);
        try {
            return decoder.decode(token);
        } catch (BadJwtException e) {
            throw e;
        } catch (JwtException e) {
            // Keys unreachable, refresh rate limit reached, or no key for the kid after a refresh.
            if (startWindow(jwksWarnings, issuer)) {
                log.warn("Cannot verify tokens of issuer {}: {}", issuer, e.getMessage());
            }
            throw new BadJwtException("Signing keys of the issuer are unavailable", e);
        }
    }

    private NimbusJwtDecoder decoder(String issuer, String slug) {
        NimbusJwtDecoder decoder = cached(issuer);
        if (decoder != null) {
            return decoder;
        }
        if (inWindow(discoveryBackoff, issuer)) {
            throw new BadJwtException("Issuer is unavailable");
        }
        CompletableFuture<NimbusJwtDecoder> mine = new CompletableFuture<>();
        CompletableFuture<NimbusJwtDecoder> running = discovering.putIfAbsent(issuer, mine);
        if (running != null) {
            return await(running);
        }
        try {
            decoder = cached(issuer); // another thread may have finished between our check and putIfAbsent
            if (decoder == null) {
                decoder = discover(issuer, slug);
                synchronized (decoders) {
                    decoders.put(issuer, decoder);
                }
            }
            mine.complete(decoder);
            return decoder;
        } catch (RuntimeException e) {
            startWindow(discoveryBackoff, issuer);
            log.warn("Discovery of issuer {} failed: {}", issuer, e.toString());
            BadJwtException failure = new BadJwtException("Issuer is unavailable", e);
            mine.completeExceptionally(failure);
            throw failure;
        } finally {
            discovering.remove(issuer, mine);
        }
    }

    private NimbusJwtDecoder await(CompletableFuture<NimbusJwtDecoder> running) {
        try {
            return running.get(discoveryWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof BadJwtException bad ? bad : new BadJwtException("Issuer is unavailable", e);
        } catch (TimeoutException e) {
            throw new BadJwtException("Issuer is unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadJwtException("Interrupted while waiting for issuer discovery", e);
        }
    }

    private NimbusJwtDecoder cached(String issuer) {
        synchronized (decoders) {
            return decoders.get(issuer);
        }
    }

    private static boolean inWindow(Map<String, Long> windows, String issuer) {
        synchronized (windows) {
            Long until = windows.get(issuer);
            return until != null && System.nanoTime() - until < 0;
        }
    }

    /** Starts a {@value #FAILURE_BACKOFF_SECONDS}-second window for the issuer; false when one is already running. */
    private boolean startWindow(Map<String, Long> windows, String issuer) {
        long now = System.nanoTime();
        synchronized (windows) {
            Long until = windows.get(issuer);
            if (until != null && now - until < 0) {
                return false;
            }
            windows.put(issuer, now + backoffNanos);
            return true;
        }
    }

    private NimbusJwtDecoder discover(String issuer, String slug) {
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
        URL jwksUrl;
        try {
            jwksUrl = URI.create(jwksUri).toURL();
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("discovery document has an invalid jwks_uri", e);
        }
        JWKSource<SecurityContext> keys = JWKSourceBuilder.<SecurityContext>create(jwksUrl, this::fetchJwks)
                .cache(JWKSourceBuilder.DEFAULT_CACHE_TIME_TO_LIVE, jwksRefreshWaitMillis)
                .refreshAheadCache(false)
                .rateLimited(TimeUnit.SECONDS.toMillis(JWKS_MIN_REFRESH_SECONDS))
                .retrying(false)
                .outageTolerant(false)
                .build();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keys).jwsAlgorithm(SignatureAlgorithm.ES256).build();
        decoder.setJwtValidator(TokenValidators.accessToken(issuer, slug, audience, tenants));
        return decoder;
    }

    private Resource fetchJwks(URL url) throws IOException {
        try {
            RequestEntity<Void> request = RequestEntity.get(url.toURI())
                    .accept(MediaType.APPLICATION_JSON, JWK_SET_JSON)
                    .build();
            ResponseEntity<String> response = rest.exchange(request, String.class);
            MediaType type = response.getHeaders().getContentType();
            return new Resource(response.getBody(), type == null ? null : type.toString());
        } catch (RestClientException | URISyntaxException e) {
            throw new IOException("JWK set request failed: " + e.getMessage(), e);
        }
    }

    private static <V> Map<String, V> lru(int maxEntries) {
        return new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > maxEntries;
            }
        };
    }
}
