package com.rbac_platform.lib.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbac_platform.lib.core.event.EventEnvelope;
import com.rbac_platform.lib.core.event.NonRetryableEventException;
import com.rbac_platform.lib.core.http.HttpClientProperties;
import com.rbac_platform.lib.core.http.ServiceRestClients;
import com.rbac_platform.lib.outbox.publish.HttpOutboxPublisher;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HttpOutboxPublisherTest {

    private static final String TOKEN = "t".repeat(32);
    private static final String PATH = "/internal/v1/events";
    private static final JsonMapper JSON = new JsonMapper();

    private final ServiceRestClients clients =
            new ServiceRestClients(new HttpClientProperties(Duration.ofSeconds(1), Duration.ofSeconds(2)));
    private final EventEnvelope event = new EventEnvelope(UUID.randomUUID(), UUID.randomUUID(), "user", "u-1",
            "user.created", JSON.createObjectNode().put("name", "a"), Map.of("k", "v"),
            Instant.parse("2026-11-02T09:00:00Z"));

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpOutboxPublisher serve(int status, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        return publisher("http://127.0.0.1:" + server.getAddress().getPort() + PATH);
    }

    private HttpOutboxPublisher publisher(String url) {
        return new HttpOutboxPublisher(clients.create(url), TOKEN);
    }

    @Test
    void deliversEnvelopeWithInternalToken() throws Exception {
        HttpOutboxPublisher publisher = serve(200, "{\"accepted\":[\"" + event.id() + "\"]}");

        assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();

        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo(PATH);
        assertThat(token.get()).isEqualTo(TOKEN);
        JsonNode sent = JSON.readTree(body.get()).get("events");
        assertThat(sent).hasSize(1);
        JsonNode envelope = sent.get(0);
        assertThat(envelope.get("id").asString()).isEqualTo(event.id().toString());
        assertThat(envelope.get("tenantId").asString()).isEqualTo(event.tenantId().toString());
        assertThat(envelope.get("aggregateType").asString()).isEqualTo("user");
        assertThat(envelope.get("aggregateId").asString()).isEqualTo("u-1");
        assertThat(envelope.get("eventType").asString()).isEqualTo("user.created");
        assertThat(envelope.get("payload").get("name").asString()).isEqualTo("a");
        assertThat(envelope.get("headers").get("k").asString()).isEqualTo("v");
        assertThat(envelope.get("occurredAt").asString()).isEqualTo("2026-11-02T09:00:00Z");
    }

    @Test
    void okWithoutEventIdIsRetryable() throws Exception {
        HttpOutboxPublisher publisher = serve(200, "{\"accepted\":[]}");
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("was not acknowledged");
    }

    @Test
    void serverErrorIsRetryable() throws Exception {
        HttpOutboxPublisher publisher = serve(503, "{}");
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NonRetryableEventException.class)
                .hasMessage("Event delivery failed with status 503");
    }

    @Test
    void tooManyRequestsIsRetryable() throws Exception {
        HttpOutboxPublisher publisher = serve(429, "{}");
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NonRetryableEventException.class)
                .hasMessage("Event delivery failed with status 429");
    }

    @Test
    void requestTimeoutIsRetryable() throws Exception {
        HttpOutboxPublisher publisher = serve(408, "{}");
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NonRetryableEventException.class)
                .hasMessage("Event delivery failed with status 408");
    }

    @Test
    void unauthorizedIsRetryable() throws Exception {
        HttpOutboxPublisher publisher = serve(401, "{}");
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NonRetryableEventException.class)
                .hasMessage("Event delivery failed with status 401");
    }

    @Test
    void forbiddenIsRetryable() throws Exception {
        HttpOutboxPublisher publisher = serve(403, "{}");
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NonRetryableEventException.class)
                .hasMessage("Event delivery failed with status 403");
    }

    @Test
    void notFoundIsRetryable() throws Exception {
        HttpOutboxPublisher publisher = serve(404, "{}");
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NonRetryableEventException.class)
                .hasMessage("Event delivery failed with status 404");
    }

    @Test
    void retryableFailureDoesNotCarryTheResponseBody() throws Exception {
        HttpOutboxPublisher publisher = serve(503, "SECRET-ECHO");
        assertThatThrownBy(() -> publisher.publish(event)).hasMessageNotContaining("SECRET-ECHO").hasNoCause();
    }

    @Test
    void badRequestIsNotRetryable() throws Exception {
        HttpOutboxPublisher publisher = serve(400, "{}");
        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessage("Event rejected with status 400");
    }

    @Test
    void connectionRefusedIsRetryable() throws Exception {
        HttpServer closed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = closed.getAddress().getPort();
        closed.stop(0);
        HttpOutboxPublisher publisher = publisher("http://127.0.0.1:" + port + PATH);
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(ResourceAccessException.class);
    }
}
