package com.rbac_platform.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fake identity provider for resource-server tests: one issuer per tenant slug at {@code {baseUrl}/t/{slug}},
 * each serving OpenID discovery and a JWKS, plus a builder for access tokens signed with its key. Slugs can be
 * made to refuse discovery (404) or hang, to prove a resource server fails closed and fast. Runs on the JDK's
 * {@link HttpServer} so tests need no external mock-server dependency.
 */
public final class TestIssuer implements AutoCloseable {

    private static final Pattern ISSUER_PATH = Pattern.compile("/t/([^/]+)/\\.well-known/(openid-configuration|jwks\\.json)");
    private static final Duration HANG = Duration.ofSeconds(10);
    private static final String KEY_ID = "test-key";
    private static final Duration LIFETIME = Duration.ofMinutes(10);

    private final HttpServer server;
    private final ExecutorService executor;
    private final String baseUrl;
    private final ECKey key;
    // Same kid as the real key, so a resource server picks it from the JWKS and the signature check is what fails.
    private final ECKey foreignKey;
    private final Set<String> refused = ConcurrentHashMap.newKeySet();
    private final Set<String> hanging = ConcurrentHashMap.newKeySet();
    private final Set<String> hangingJwks = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> discoveryRequests = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> jwksRequests = new ConcurrentHashMap<>();

    private TestIssuer(HttpServer server, ExecutorService executor, ECKey key, ECKey foreignKey) {
        this.server = server;
        this.executor = executor;
        this.key = key;
        this.foreignKey = foreignKey;
        this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public static TestIssuer start() {
        try {
            ECKey key = new ECKeyGenerator(Curve.P_256).keyID(KEY_ID).generate();
            ECKey foreignKey = new ECKeyGenerator(Curve.P_256).keyID(KEY_ID).generate();
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            // One thread per exchange: a hanging slug must not block the others.
            ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "test-issuer");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            TestIssuer issuer = new TestIssuer(server, executor, key, foreignKey);
            server.createContext("/", issuer::handle);
            server.start();
            return issuer;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot generate the test signing key", e);
        }
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String issuer(String slug) {
        return baseUrl + "/t/" + slug;
    }

    /** Discovery for this slug answers 404, as identity does for an unknown tenant. */
    public void refuse(String slug) {
        refused.add(slug);
    }

    /** Discovery for this slug does not answer for 10 seconds. */
    public void hang(String slug) {
        hanging.add(slug);
    }

    /** The JWKS of this slug does not answer for 10 seconds. */
    public void hangJwks(String slug) {
        hangingJwks.add(slug);
    }

    /** How many discovery requests this slug has received (refused and hanging ones included). */
    public int discoveryRequests(String slug) {
        AtomicInteger count = discoveryRequests.get(slug);
        return count == null ? 0 : count.get();
    }

    /** How many JWKS requests this slug has received (hanging ones included). */
    public int jwksRequests(String slug) {
        AtomicInteger count = jwksRequests.get(slug);
        return count == null ? 0 : count.get();
    }

    public TokenBuilder token(String slug) {
        return new TokenBuilder(slug);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Matcher path = ISSUER_PATH.matcher(exchange.getRequestURI().getPath());
            if (!"GET".equals(exchange.getRequestMethod()) || !path.matches()) {
                respond(exchange, 404, "{}");
                return;
            }
            String slug = path.group(1);
            if (path.group(2).equals("jwks.json")) {
                jwksRequests.computeIfAbsent(slug, s -> new AtomicInteger()).incrementAndGet();
                if (hangingJwks.contains(slug) && !hang()) {
                    return;
                }
                respond(exchange, 200, new JWKSet(key.toPublicJWK()).toString());
                return;
            }
            discoveryRequests.computeIfAbsent(slug, s -> new AtomicInteger()).incrementAndGet();
            if (hanging.contains(slug) && !hang()) {
                return;
            }
            if (refused.contains(slug)) {
                respond(exchange, 404, "{}");
                return;
            }
            respond(exchange, 200, "{\"issuer\":\"" + issuer(slug) + "\",\"jwks_uri\":\"" + baseUrl + "/t/" + slug
                    + "/.well-known/jwks.json\"}");
        }
    }

    /** Sleeps through the hang; false when interrupted (the server is closing). */
    private static boolean hang() {
        try {
            Thread.sleep(HANG.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /**
     * An access token as identity would issue it: {@code iss = issuer(slug)}, {@code aud = rbac-api},
     * {@code typ = at+jwt}, {@code exp = now + 10 minutes}, random {@code sub}, {@code sid} and {@code jti},
     * {@code amr = ["pwd"]}, ES256. {@code tid} is only set by {@link #tenant(UUID)} or {@link #claim}.
     */
    public final class TokenBuilder {

        private final Map<String, Object> claims = new LinkedHashMap<>();
        private final Set<String> removed = new HashSet<>();
        private String type = "at+jwt";
        private String keyId = KEY_ID;
        private boolean foreign;

        private TokenBuilder(String slug) {
            Instant now = Instant.now();
            claims.put("iss", issuer(slug));
            claims.put("sub", UUID.randomUUID().toString());
            claims.put("sid", UUID.randomUUID().toString());
            claims.put("aud", "rbac-api");
            claims.put("amr", List.of("pwd"));
            claims.put("iat", Date.from(now));
            claims.put("exp", Date.from(now.plus(LIFETIME)));
            claims.put("jti", UUID.randomUUID().toString());
        }

        public TokenBuilder tenant(UUID tenantId) {
            return claim("tid", tenantId.toString());
        }

        public TokenBuilder subject(UUID subjectId) {
            return claim("sub", subjectId.toString());
        }

        public TokenBuilder session(UUID sessionId) {
            return claim("sid", sessionId.toString());
        }

        public TokenBuilder amr(String... methods) {
            return claim("amr", Arrays.asList(methods));
        }

        public TokenBuilder audience(String audience) {
            return claim("aud", audience);
        }

        /** The {@code typ} header. */
        public TokenBuilder type(String type) {
            this.type = type;
            return this;
        }

        /** Also moves {@code iat} to 10 minutes before, as for a token identity issued that long ago. */
        public TokenBuilder expiresAt(Instant expiresAt) {
            claim("iat", Date.from(expiresAt.minus(LIFETIME)));
            return claim("exp", Date.from(expiresAt));
        }

        /** Sets any claim; an {@link Instant} is written as a NumericDate, a {@link UUID} as its string. */
        public TokenBuilder claim(String name, Object value) {
            Object json = value instanceof Instant instant ? Date.from(instant)
                    : value instanceof UUID uuid ? uuid.toString() : value;
            claims.put(name, json);
            removed.remove(name);
            return this;
        }

        /** Leaves the claim out of the token, whatever else set it. */
        public TokenBuilder without(String claim) {
            removed.add(claim);
            return this;
        }

        /** The {@code kid} header; signing still uses the issuer's key. */
        public TokenBuilder keyId(String keyId) {
            this.keyId = keyId;
            return this;
        }

        /** Signs with a key the issuer does not publish (same kid, so only the signature check can catch it). */
        public TokenBuilder signedByForeignKey() {
            foreign = true;
            return this;
        }

        public String sign() {
            JWTClaimsSet.Builder set = new JWTClaimsSet.Builder();
            claims.forEach((name, value) -> {
                if (!removed.contains(name)) {
                    set.claim(name, value);
                }
            });
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                    .type(new JOSEObjectType(type))
                    .keyID(keyId)
                    .build();
            SignedJWT jwt = new SignedJWT(header, set.build());
            try {
                jwt.sign(new ECDSASigner(foreign ? foreignKey : key));
            } catch (JOSEException e) {
                throw new IllegalStateException("cannot sign the test token", e);
            }
            return jwt.serialize();
        }
    }
}
