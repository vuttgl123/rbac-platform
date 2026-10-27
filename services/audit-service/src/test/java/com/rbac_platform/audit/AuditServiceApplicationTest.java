package com.rbac_platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.testsupport.AppDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditServiceApplicationTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppDatabase.register(registry, "audit_app");
    }

    @Autowired
    private JdbcClient jdbc;

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void connectsAsItsOwnRole() {
        assertThat(jdbc.sql("select current_user").query(String.class).single()).isEqualTo("audit_app");
    }

    @Test
    void healthIsPublicButHidesDetails() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components").doesNotContain("db");
    }

    @Test
    void rejectedRequestsStillCarryARequestId() throws Exception {
        HttpResponse<String> response = get("/anything");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("X-Request-Id")).hasValueSatisfying(id -> assertThat(id).matches(UUID_PATTERN));
    }

}
