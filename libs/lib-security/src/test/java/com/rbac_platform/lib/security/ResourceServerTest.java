package com.rbac_platform.lib.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import com.rbac_platform.testsupport.Sql;
import com.rbac_platform.testsupport.TestIssuer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = SecurityTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResourceServerTest {

    private static final TestIssuer issuer = TestIssuer.start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppDatabase.register(registry, "identity_app");
        registry.add("rbac.security.issuer-prefix", () -> issuer.baseUrl() + "/t/");
        registry.add("rbac.security.public-paths[0]", () -> "/test/public/**");
        registry.add("rbac.security.tenant-cache-ttl", () -> "200ms");
        // Re-enables Boot's generated user despite oauth2 on the classpath; only our beans make it back off.
        registry.add("spring.security.user.name", () -> "someone");
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final UUID tenantId = MigratedDatabase.get().insertTenant();

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("requestId").asString())
                .isEqualTo(response.headers().firstValue(RequestLoggingFilter.HEADER).orElseThrow());
    }

    private void assertUnauthenticated(HttpResponse<String> response) {
        assertProblem(response, 401, "unauthenticated");
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Bearer");
    }

    @Test
    void validTokenReachesControllerAsCaller() throws Exception {
        UUID subject = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        String token = issuer.token("acme").tenant(tenantId).subject(subject).session(session).sign();

        HttpResponse<String> response = get("/test/whoami", token);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("tenantId").asString()).isEqualTo(tenantId.toString());
        assertThat(body.get("subjectId").asString()).isEqualTo(subject.toString());
        assertThat(body.get("sessionId").asString()).isEqualTo(session.toString());
        assertThat(body.get("amr").toString()).isEqualTo("[\"pwd\"]");
    }

    @Test
    void missingTokenIsProblemJson() throws Exception {
        HttpResponse<String> response = get("/test/whoami", null);

        assertUnauthenticated(response);
        assertThat(response.headers().allValues("WWW-Authenticate")).noneMatch(h -> h.contains("Basic"));
    }

    @Test
    void expiredTokenSaysTokenExpired() throws Exception {
        String token = issuer.token("acme").tenant(tenantId).expiresAt(Instant.now().minus(Duration.ofMinutes(5))).sign();

        assertProblem(get("/test/whoami", token), 401, "token_expired");
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        assertUnauthenticated(get("/test/whoami", issuer.token("acme").tenant(tenantId).audience("other").sign()));
    }

    @Test
    void challengeTokenIsRejected() throws Exception {
        assertUnauthenticated(get("/test/whoami", issuer.token("acme").tenant(tenantId).type("challenge+jwt").sign()));
    }

    @Test
    void foreignSignatureIsRejected() throws Exception {
        assertUnauthenticated(get("/test/whoami", issuer.token("acme").tenant(tenantId).signedByForeignKey().sign()));
    }

    @Test
    void issuerOutsidePrefixIsRejected() throws Exception {
        String token = issuer.token("acme").tenant(tenantId).claim("iss", "http://evil.example/t/acme").sign();

        assertUnauthenticated(get("/test/whoami", token));
    }

    @Test
    void nestedPathIssuerIsRejected() throws Exception {
        String token = issuer.token("acme").tenant(tenantId).claim("iss", issuer.baseUrl() + "/t/acme/extra").sign();

        assertUnauthenticated(get("/test/whoami", token));
    }

    @Test
    void missingTenantClaimIsRejected() throws Exception {
        assertUnauthenticated(get("/test/whoami", issuer.token("acme").tenant(tenantId).without("tid").sign()));
    }

    @Test
    void nonUuidSubjectIsRejected() throws Exception {
        assertUnauthenticated(get("/test/whoami", issuer.token("acme").tenant(tenantId).claim("sub", "alice").sign()));
    }

    @Test
    void unknownSlugIs401() throws Exception {
        issuer.refuse("ghost");

        assertUnauthenticated(get("/test/whoami", issuer.token("ghost").tenant(tenantId).sign()));
    }

    @Test
    void hangingDiscoveryIs401Quickly() throws Exception {
        issuer.hang("slow");
        String token = issuer.token("slow").tenant(tenantId).sign();

        long start = System.nanoTime();
        HttpResponse<String> response = get("/test/whoami", token);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertUnauthenticated(response);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void suspendedTenantIsRejectedAfterCacheTtl() throws Exception {
        String token = issuer.token("acme").tenant(tenantId).sign();
        assertThat(get("/test/whoami", token).statusCode()).isEqualTo(200);

        MigratedDatabase.get().inTx(null, null,
                c -> Sql.exec(c, "update tenancy.tenants set status = 'suspended' where id = ?", tenantId));
        Thread.sleep(300);

        assertUnauthenticated(get("/test/whoami", token));
    }

    @Test
    void discoveryIsFetchedOncePerIssuer() throws Exception {
        assertThat(get("/test/whoami", issuer.token("once").tenant(tenantId).sign()).statusCode()).isEqualTo(200);
        assertThat(get("/test/whoami", issuer.token("once").tenant(tenantId).sign()).statusCode()).isEqualTo(200);

        assertThat(issuer.discoveryRequests("once")).isEqualTo(1);
    }

    @Test
    void publicPathNeedsNoToken() throws Exception {
        HttpResponse<String> response = get("/test/public/ping", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("pong");
    }

    @Test
    void callerOnPublicPathWithoutTokenIs401() throws Exception {
        assertProblem(get("/test/public/caller", null), 401, "unauthenticated");
    }

    @Test
    void accessDeniedFromControllerIsForbiddenProblem() throws Exception {
        String token = issuer.token("acme").tenant(tenantId).sign();

        assertProblem(get("/test/denied", token), 403, "forbidden");
    }

    @Test
    void noGeneratedPassword(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContainIgnoringCase("generated security password");
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }
}
