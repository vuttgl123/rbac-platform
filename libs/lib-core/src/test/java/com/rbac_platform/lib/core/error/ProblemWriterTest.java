package com.rbac_platform.lib.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProblemWriterTest {

    @Test
    void writesFlatProblemJsonWithAPlainMapper() throws Exception {
        JsonMapper plain = JsonMapper.builder().build();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestLoggingFilter.REQUEST_ATTRIBUTE, "req-9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ProblemWriter(plain).write(request, response, HttpStatus.UNAUTHORIZED, "unauthenticated", "No token");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode body = plain.readTree(response.getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo("unauthenticated");
        assertThat(body.get("requestId").asString()).isEqualTo("req-9");
        assertThat(body.get("title").asString()).isEqualTo("Unauthorized");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.has("properties")).isFalse();
    }
}
