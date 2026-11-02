package com.rbac_platform.lib.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.TestIssuer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tenant lookup runs inside the bearer-token filter, before Spring MVC. When the database fails there the
 * answer is a 500 problem+json with the request id, never Boot's plain JSON error body and never a 200.
 */
@SpringBootTest(classes = SecurityTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TenantLookupFailureTest.BrokenDatabase.class)
class TenantLookupFailureTest {

    private static final String DB_DETAIL = "connection to db-secret-host refused";
    private static final TestIssuer issuer = TestIssuer.start();

    @TestConfiguration
    static class BrokenDatabase {

        @Bean
        TenantStatusCache failingTenantStatusCache() {
            return new TenantStatusCache(null, Duration.ofSeconds(30)) {
                @Override
                public UUID activeTenant(String slug) {
                    throw new DataAccessResourceFailureException(DB_DETAIL);
                }
            };
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppDatabase.register(registry, "identity_app");
        registry.add("rbac.security.issuer-prefix", () -> issuer.baseUrl() + "/t/");
        registry.add("rbac.security.public-paths[0]", () -> "/test/public/**");
        registry.add("rbac.security.internal-token", () -> "x".repeat(40));
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String path) throws Exception {
        String token = issuer.token("acme").tenant(UUID.randomUUID()).sign();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertInternalError(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo("internal_error");
        assertThat(body.get("detail").asString()).isEqualTo("Unexpected error");
        assertThat(body.get("requestId").asString())
                .isEqualTo(response.headers().firstValue(RequestLoggingFilter.HEADER).orElseThrow());
        assertThat(response.body()).doesNotContain("db-secret-host").doesNotContain("DataAccess");
    }

    @Test
    void databaseFailureDuringTokenCheckIsProblemJson() throws Exception {
        assertInternalError(get("/test/whoami"));
    }

    @Test
    void databaseFailureOnPublicPathWithTokenIsNotOk() throws Exception {
        assertInternalError(get("/test/public/ping"));
    }
}
