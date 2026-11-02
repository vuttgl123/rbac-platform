package com.rbac_platform.lib.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import com.rbac_platform.testsupport.Sql;
import com.rbac_platform.testsupport.TestIssuer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = SecurityTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalTokenFilterTest {

    private static final String SECRET = "x".repeat(40);
    private static final TestIssuer issuer = TestIssuer.start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppDatabase.register(registry, "identity_app");
        registry.add("rbac.security.issuer-prefix", () -> issuer.baseUrl() + "/t/");
        registry.add("rbac.security.internal-token", () -> SECRET);
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int port;

    private HttpResponse<String> ping(String internalToken, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/test/ping"))
                .timeout(Duration.ofSeconds(20)).GET();
        if (internalToken != null) {
            request.header("X-Internal-Token", internalToken);
        }
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertRejected(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo("unauthenticated");
        assertThat(body.get("requestId")).isNotNull();
        assertThat(body.get("requestId").asString()).isNotBlank();
        assertThat(response.body()).doesNotContain("reached");
    }

    @Test
    void correctTokenPasses() throws Exception {
        HttpResponse<String> response = ping(SECRET, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("reached");
    }

    @Test
    void wrongTokenIs401() throws Exception {
        assertRejected(ping("y".repeat(40), null));
    }

    @Test
    void missingTokenIs401() throws Exception {
        assertRejected(ping(null, null));
    }

    @Test
    void bearerTokenDoesNotOpenInternal() throws Exception {
        UUID tenant = MigratedDatabase.get().insertTenant();
        String slug = MigratedDatabase.get().inTx(null, null,
                c -> Sql.one(c, String.class, "select slug from tenancy.tenants where id = ?", tenant));

        assertRejected(ping(null, issuer.token(slug).tenant(tenant).sign()));
        assertRejected(ping("y".repeat(40), issuer.token(slug).tenant(tenant).sign()));
    }

    @Test
    void correctTokenWithBearerStillPasses() throws Exception {
        UUID tenant = MigratedDatabase.get().insertTenant();
        String slug = MigratedDatabase.get().inTx(null, null,
                c -> Sql.one(c, String.class, "select slug from tenancy.tenants where id = ?", tenant));

        assertThat(ping(SECRET, issuer.token(slug).tenant(tenant).sign()).statusCode()).isEqualTo(200);
    }

    /** Sends the request line verbatim: java.net.http would normalise some of these paths. */
    private String rawStatusAndBody(String rawPath, String bearer) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            String request = "GET " + rawPath + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + "Authorization: Bearer " + bearer + "\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"//internal/test/ping", "/x/../internal/test/ping",
            "/internal;a=b/test/ping", "/internal%2Ftest/ping", "/INTERNAL/test/ping", "/internal/test/ping/",
            "/%69nternal/test/ping"})
    void pathTrickeryNeverReachesTheControllerWithoutTheToken(String rawPath) throws Exception {
        UUID tenant = MigratedDatabase.get().insertTenant();
        String slug = MigratedDatabase.get().inTx(null, null,
                c -> Sql.one(c, String.class, "select slug from tenancy.tenants where id = ?", tenant));

        String response = rawStatusAndBody(rawPath, issuer.token(slug).tenant(tenant).sign());

        assertThat(response).startsWith("HTTP/1.1 ");
        assertThat(response.split(" ")[1]).as(response).isNotEqualTo("200");
        assertThat(response.substring(response.indexOf("\r\n\r\n") + 4)).doesNotContain("reached");
    }

    @Configuration
    @EnableConfigurationProperties(SecurityProperties.class)
    static class PropertiesOnly {
    }

    @Test
    void shortTokenFailsStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("rbac.security.issuer-prefix=https://id.example.com/t/",
                        "rbac.security.internal-token=short-10ch")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = context.getStartupFailure();
                    StringBuilder messages = new StringBuilder();
                    for (Throwable t = root; t != null; t = t.getCause()) {
                        messages.append(t.getMessage()).append('\n');
                    }
                    assertThat(messages.toString()).contains("rbac.security.internal-token");
                });
    }
}
