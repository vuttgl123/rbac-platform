package com.rbac_platform.lib.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.TestIssuer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A service that defines its own SecurityFilterChain replaces the main chain, but {@code /internal/**} keeps its
 * guard: the internal chain does not back off.
 */
@SpringBootTest(classes = SecurityTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ServiceDefinedChainTest.ServiceChain.class)
class ServiceDefinedChainTest {

    private static final String SECRET = "x".repeat(40);
    private static final TestIssuer issuer = TestIssuer.start();

    /** A deliberately careless service chain: everything is open. */
    @TestConfiguration
    static class ServiceChain {

        @Bean
        SecurityFilterChain serviceSecurityFilterChain(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
        }
    }

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

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    private HttpResponse<String> get(String path, String internalToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (internalToken != null) {
            request.header(InternalTokenFilter.HEADER, internalToken);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void serviceChainReplacesTheMainChain() throws Exception {
        assertThat(context.getBeansOfType(SecurityFilterChain.class))
                .containsOnlyKeys("serviceSecurityFilterChain", "rbacInternalSecurityFilterChain");
        // The service's chain is in charge outside /internal/**: no token needed for a protected path.
        assertThat(get("/test/public/ping", null).statusCode()).isEqualTo(200);
    }

    @Test
    void internalPathStillNeedsTheToken() throws Exception {
        HttpResponse<String> response = get("/internal/test/ping", null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"").doesNotContain("reached");
        assertThat(get("/internal/test/ping", "y".repeat(40)).statusCode()).isEqualTo(401);
    }

    @Test
    void internalPathWithTheTokenPasses() throws Exception {
        HttpResponse<String> response = get("/internal/test/ping", SECRET);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("reached");
    }
}
