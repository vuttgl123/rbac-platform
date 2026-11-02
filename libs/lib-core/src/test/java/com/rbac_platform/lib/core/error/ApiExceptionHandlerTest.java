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

@SpringBootTest(classes = CoreTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiExceptionHandlerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("requestId").asString())
                .isEqualTo(response.headers().firstValue(RequestLoggingFilter.HEADER).orElseThrow());
    }

    @Test
    void apiExceptionKeepsStatusAndCode() throws Exception {
        HttpResponse<String> response = send("GET", "/test/conflict", null);

        assertProblem(response, 409, "slug_taken");
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("detail").asString()).isEqualTo("Slug acme is taken");
        assertThat(body.get("title").asString()).isEqualTo("Conflict");
        assertThat(body.get("type").asString()).isEqualTo("about:blank");
        assertThat(body.has("errors")).isFalse();
    }

    @Test
    void beanValidationListsFields() throws Exception {
        HttpResponse<String> response = send("POST", "/test/validate", "{\"name\":\"\",\"email\":\"x\"}");

        assertProblem(response, 400, "validation_failed");
        JsonNode errors = json.readTree(response.body()).get("errors");
        assertThat(errors.toString())
                .contains("{\"field\":\"name\",\"code\":\"not_blank\"}")
                .contains("{\"field\":\"email\",\"code\":\"email\"}");
    }

    @Test
    void malformedJsonIsValidationError() throws Exception {
        HttpResponse<String> response = send("POST", "/test/validate", "{");

        assertProblem(response, 400, "validation_failed");
        assertThat(response.body()).doesNotContain("Exception");
    }

    @Test
    void typeMismatchNamesTheParameter() throws Exception {
        HttpResponse<String> response = send("GET", "/test/param?limit=abc", null);

        assertProblem(response, 400, "validation_failed");
        assertThat(json.readTree(response.body()).get("errors").toString())
                .isEqualTo("[{\"field\":\"limit\",\"code\":\"type_mismatch\"}]");
    }

    @Test
    void unknownRouteIsNotFound() throws Exception {
        assertProblem(send("GET", "/no/such/path", null), 404, "not_found");
    }

    @Test
    void wrongMethodIsMethodNotAllowed() throws Exception {
        assertProblem(send("DELETE", "/test/conflict", null), 405, "method_not_allowed");
    }

    @Test
    void unexpectedErrorHidesInternals() throws Exception {
        HttpResponse<String> response = send("GET", "/test/boom", null);

        assertProblem(response, 500, "internal_error");
        // Spring adds "instance":"/test/boom" (the request path), so assert on the exception message, not the substring.
        assertThat(json.readTree(response.body()).get("detail").asString()).isEqualTo("Unexpected error");
        assertThat(response.body()).doesNotContain("\"boom\"").doesNotContain("IllegalStateException");
    }
}
