package com.rbac_platform.lib.core.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.ResourceAccessException;

class ServiceRestClientsTest {

    private HttpServer server;
    private final AtomicReference<String> seenRequestId = new AtomicReference<>();
    private volatile long sleepMillis = 0;

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    private String start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ping", exchange -> {
            seenRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "pong".getBytes();
            try {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (java.io.IOException ignored) {
                // client gave up (timeout test)
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ServiceRestClients clients(Duration read) {
        return new ServiceRestClients(new HttpClientProperties(Duration.ofSeconds(1), read));
    }

    @Test
    void forwardsRequestIdFromMdc() throws Exception {
        MDC.put("requestId", "req-7");
        String body = clients(Duration.ofSeconds(2)).create(start()).get().uri("/ping").retrieve().body(String.class);
        assertThat(body).isEqualTo("pong");
        assertThat(seenRequestId.get()).isEqualTo("req-7");
    }

    @Test
    void noMdcMeansNoHeader() throws Exception {
        clients(Duration.ofSeconds(2)).create(start()).get().uri("/ping").retrieve().body(String.class);
        assertThat(seenRequestId.get()).isNull();
    }

    @Test
    void slowServerTimesOut() throws Exception {
        sleepMillis = 1000;
        var client = clients(Duration.ofMillis(200)).create(start());
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/ping").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(900));
    }
}
