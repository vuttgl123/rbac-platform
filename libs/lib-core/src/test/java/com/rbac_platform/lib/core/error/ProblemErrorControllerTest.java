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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Failures outside Spring MVC (a filter throwing, the container's sendError) reach the error page; they must still
 * be problem+json with a code and the request id, and never echo the exception or error message.
 */
@SpringBootTest(classes = CoreTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProblemErrorControllerTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String path, String requestId, String accept) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (requestId != null) {
            request.header(RequestLoggingFilter.HEADER, requestId);
        }
        if (accept != null) {
            request.header("Accept", accept);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("type").asString()).isEqualTo("about:blank");
        assertThat(body.get("title")).isNotNull();
        assertThat(body.get("requestId").asString())
                .isEqualTo(response.headers().firstValue(RequestLoggingFilter.HEADER).orElseThrow());
        assertThat(response.body()).doesNotContain(FailingFilter.SECRET).doesNotContain("IllegalStateException")
                .doesNotContain("timestamp");
        return body;
    }

    @Test
    void errorBodyCarriesTheRequestIdOfTheResponseHeader() throws Exception {
        HttpResponse<String> response = get("/test/filter/boom", "err-1", null);

        assertThat(response.headers().firstValue(RequestLoggingFilter.HEADER)).contains("err-1");
        JsonNode body = assertProblem(response, 500, "internal_error");
        assertThat(body.get("requestId").asString()).isEqualTo("err-1");
    }

    @Test
    void errorBodyCarriesAGeneratedIdWhenNoneWasSent() throws Exception {
        HttpResponse<String> response = get("/test/filter/boom", null, null);

        String id = response.headers().firstValue(RequestLoggingFilter.HEADER).orElseThrow();
        assertThat(id).matches(UUID_PATTERN);
        assertProblem(response, 500, "internal_error");
    }

    @Test
    void filterFailureHasGenericDetail() throws Exception {
        JsonNode body = assertProblem(get("/test/filter/boom", null, null), 500, "internal_error");

        assertThat(body.get("detail").asString()).isEqualTo("Unexpected error");
    }

    @Test
    void htmlClientStillGetsProblemJson() throws Exception {
        assertProblem(get("/test/filter/boom", null, "text/html"), 500, "internal_error");
    }

    @Test
    void containerSendErrorKeepsItsStatusAndMapsTheCode() throws Exception {
        assertProblem(get("/test/filter/unauthorized", null, null), 401, "unauthenticated");
        assertProblem(get("/test/filter/teapot", null, null), 418, "validation_failed");
        JsonNode unavailable = assertProblem(get("/test/filter/unavailable", null, null), 503, "internal_error");
        assertThat(unavailable.get("detail").asString()).isEqualTo("Unexpected error");
    }
}
