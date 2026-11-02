package com.rbac_platform.lib.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbac_platform.lib.core.error.ApiException;
import com.rbac_platform.lib.core.http.HttpClientProperties;
import com.rbac_platform.lib.core.http.ServiceRestClients;
import com.rbac_platform.lib.security.Caller;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HttpAccessCheckerTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID tenant = UUID.randomUUID();
    private final Caller caller = new Caller(tenant, "user", UUID.randomUUID(), null, List.of("pwd"), "iss", "tok-123");
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestId = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        MDC.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpAccessChecker checkerAnswering(int status, String response, long sleepMillis, Duration readTimeout)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // The client may already have given up (timeout test).
            } finally {
                exchange.close();
            }
        });
        server.start();
        ServiceRestClients clients = new ServiceRestClients(new HttpClientProperties(Duration.ofSeconds(1), readTimeout));
        return new HttpAccessChecker(clients.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private HttpAccessChecker checkerAnswering(int status, String response) throws IOException {
        return checkerAnswering(status, response, 0, Duration.ofSeconds(2));
    }

    private void assertUnavailable(HttpAccessChecker checker) {
        assertThatThrownBy(() -> checker.require(caller, "admin", ObjectRef.tenant(tenant)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("authz_unavailable");
                });
    }

    @Test
    void allowedReturnsAndSendsCallerToken() throws Exception {
        HttpAccessChecker checker = checkerAnswering(200, "{\"result\":\"allowed\"}");
        MDC.put("requestId", "req-42");

        checker.require(caller, "admin", ObjectRef.tenant(tenant));

        assertThat(path.get()).isEqualTo("POST /v1/check");
        JsonNode sent = json.readTree(body.get());
        assertThat(sent.get("permission").asString()).isEqualTo("admin");
        assertThat(sent.get("object").get("type").asString()).isEqualTo("tenant");
        assertThat(sent.get("object").get("id").asString()).isEqualTo(tenant.toString());
        assertThat(sent.has("subject")).isFalse();
        assertThat(authorization.get()).isEqualTo("Bearer tok-123");
        assertThat(requestId.get()).isEqualTo("req-42");
    }

    @Test
    void deniedIsForbidden() throws Exception {
        HttpAccessChecker checker = checkerAnswering(200, "{\"result\":\"denied\"}");

        assertThatThrownBy(() -> checker.require(caller, "admin", ObjectRef.tenant(tenant)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.code()).isEqualTo("forbidden");
                    assertThat(e.getMessage()).isEqualTo("Missing admin on tenant");
                });
    }

    @Test
    void conditionalIsForbidden() throws Exception {
        HttpAccessChecker checker = checkerAnswering(200, "{\"result\":\"conditional\"}");

        assertThatThrownBy(() -> checker.require(caller, "admin", ObjectRef.tenant(tenant)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void serverErrorIsUnavailable() throws Exception {
        assertUnavailable(checkerAnswering(500, "{\"result\":\"allowed\"}"));
    }

    @Test
    void timeoutIsUnavailable() throws Exception {
        assertUnavailable(checkerAnswering(200, "{\"result\":\"allowed\"}", 1000, Duration.ofMillis(200)));
    }

    @Test
    void garbageIsUnavailable() throws Exception {
        assertUnavailable(checkerAnswering(200, "<html>"));
    }

    @Test
    void unknownResultIsUnavailable() throws Exception {
        assertUnavailable(checkerAnswering(200, "{\"result\":\"maybe\"}"));
    }

    @Test
    void unreachableIsUnavailable() throws Exception {
        HttpAccessChecker checker = checkerAnswering(200, "{}");
        server.stop(0);

        assertUnavailable(checker);
    }
}
