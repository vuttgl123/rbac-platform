package com.rbac_platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.rbac_platform.lib.db.TenantScope;
import com.rbac_platform.lib.outbox.OutboxMessage;
import com.rbac_platform.lib.outbox.OutboxWriter;
import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import com.rbac_platform.testsupport.Sql;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityServiceApplicationTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppDatabase.register(registry, "identity_app");
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TenantScope tenantScope;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private JsonMapper json;

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void connectsAsItsOwnRole() {
        assertThat(jdbc.sql("select current_user").query(String.class).single()).isEqualTo("identity_app");
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

    @Test
    void noGeneratedLoginExistsAndAnyBasicCredentialIsRejected(CapturedOutput output) throws Exception {
        String credentials = Base64.getEncoder().encodeToString("user:anything".getBytes());
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/anything"))
                .header("Authorization", "Basic " + credentials).GET().build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(output.getAll()).doesNotContain("generated security password");
    }

    @Test
    void refusesToStartWithAnotherServicesLogin() {
        MigratedDatabase db = AppDatabase.enableLogin("authz_app");

        // Command-line arguments outrank the application.yaml and db-defaults.yaml values for these keys.
        assertThatThrownBy(() -> new SpringApplicationBuilder(IdentityServiceApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + db.jdbcUrl(),
                "--spring.datasource.username=authz_app",
                // Without a password the run would fail on the unresolved ${IDENTITY_DB_PASSWORD}, not on the role check.
                "--spring.datasource.password=unused"))
                .satisfies(failure -> {
                    Throwable cause = failure;
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    assertThat(cause.getMessage()).contains("expects database role 'identity_app'");
                });
    }

    @Test
    void outboxEventIsRelayedAndRemoved(CapturedOutput output) {
        UUID tenant = MigratedDatabase.get().insertTenant();
        String aggregateId = UUID.randomUUID().toString();

        tenantScope.call(tenant, () -> {
            outboxWriter.append(OutboxMessage.of("probe", aggregateId, "probe.happened", json.readTree("{\"id\":\"" + aggregateId + "\"}")));
            return null;
        });

        // The shared database may hold other tests' rows, so only this message's row is checked. The budget is
        // generous for a loaded build machine; untilAsserted returns as soon as it holds.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            long rows = MigratedDatabase.get().inTx(null, null, c -> Sql.count(c,
                    "select count(*) from identity.outbox where aggregate_id = ?", aggregateId));
            assertThat(rows).isZero();
            assertThat(output.getAll()).contains("probe/" + aggregateId);
        });
    }
}
