package com.rbac_platform.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rbac_platform.lib.batch.JobLaunchService;
import com.rbac_platform.lib.batch.PartitionMaintenanceJob;
import com.rbac_platform.lib.batch.ScheduledJobRunner;
import com.rbac_platform.lib.db.TenantScope;
import com.rbac_platform.lib.outbox.OutboxMessage;
import com.rbac_platform.lib.outbox.OutboxWriter;
import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import com.rbac_platform.testsupport.Sql;
import com.rbac_platform.testsupport.TestIssuer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
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
class AuthzServiceApplicationTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final TestIssuer issuer = TestIssuer.start();

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("rbac.security.issuer-prefix", () -> issuer.baseUrl() + "/t/");
        registry.add("rbac.security.internal-token", () -> "x".repeat(40));
        AppDatabase.register(registry, "authz_app");
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TenantScope tenantScope;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JobLaunchService jobLaunchService;

    @Autowired
    private ScheduledJobRunner scheduledJobRunner;

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void connectsAsItsOwnRole() {
        assertThat(jdbc.sql("select current_user").query(String.class).single()).isEqualTo("authz_app");
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
                    "select count(*) from authz.outbox where aggregate_id = ?", aggregateId));
            assertThat(rows).isZero();
            assertThat(output.getAll()).contains("probe/" + aggregateId);
        });
    }

    @Test
    void partitionMaintenanceJobIsScheduledAndRuns() {
        assertThat(scheduledJobRunner).isNotNull();

        JobExecution execution = jobLaunchService.launch(PartitionMaintenanceJob.NAME,
                Map.of("run", UUID.randomUUID().toString()));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void basicCredentialsAreRejectedWithoutBasicChallenge(CapturedOutput output) throws Exception {
        String credentials = Base64.getEncoder().encodeToString("user:anything".getBytes());
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/anything"))
                .header("Authorization", "Basic " + credentials).GET().build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(String.join(",", response.headers().allValues("WWW-Authenticate"))).doesNotContain("Basic");
        assertThat(output.getAll()).doesNotContain("generated security password");
    }

    @Test
    void validTokenIsAuthenticated() throws Exception {
        UUID tenant = MigratedDatabase.get().insertTenant();
        String slug = MigratedDatabase.get().inTx(null, null,
                c -> Sql.one(c, String.class, "select slug from tenancy.tenants where id = ?", tenant));

        HttpResponse<String> response = get("/anything", issuer.token(slug).tenant(tenant).sign());

        // Past authentication, and there is no such route.
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"not_found\"");
    }

    @Test
    void unauthenticatedIsProblemJson() throws Exception {
        HttpResponse<String> response = get("/anything");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }
}
