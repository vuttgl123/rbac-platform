package com.rbac_platform.lib.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.lib.core.CoreTestApplication;
import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(classes = CoreTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestIdErrorAttributesTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @LocalServerPort
    private int port;

    private HttpResponse<String> getBoom(String requestId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test/boom"));
        if (requestId != null) {
            request.header(RequestLoggingFilter.HEADER, requestId);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void errorBodyCarriesTheRequestIdOfTheResponseHeader() throws Exception {
        HttpResponse<String> response = getBoom("err-1");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue(RequestLoggingFilter.HEADER)).contains("err-1");
        assertThat(response.body()).contains("\"requestId\":\"err-1\"");
    }

    @Test
    void errorBodyCarriesAGeneratedIdWhenNoneWasSent() throws Exception {
        HttpResponse<String> response = getBoom(null);

        String id = response.headers().firstValue(RequestLoggingFilter.HEADER).orElseThrow();
        assertThat(id).matches(UUID_PATTERN);
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("\"requestId\":\"" + id + "\"");
    }
}
