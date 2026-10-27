package com.rbac_platform.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbac_platform.AppDatabase;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingFilterTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppDatabase.register(registry);
    }

    @Autowired
    private RequestLoggingFilter filter;

    private MockHttpServletResponse run(MockHttpServletRequest request, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static FilterChain logging(String message) {
        return (req, res) -> LoggerFactory.getLogger("test.handler").info(message);
    }

    @Test
    void everyLogLineOfARequestCarriesTheGeneratedRequestId(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest("GET", "/things"), logging("handling things"));

        String id = response.getHeader(RequestLoggingFilter.HEADER);
        assertThat(id).matches(UUID_PATTERN);
        assertThat(output).containsPattern("\\[" + id + " -\\] \\S.*handling things");
        assertThat(output).containsPattern("\\[" + id + " -\\].*GET /things -> 200 in \\d+ ms");
    }

    @Test
    void keepsASafeIncomingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/things");
        request.addHeader(RequestLoggingFilter.HEADER, "gateway-42.a_b:c");
        assertThat(run(request, logging("x")).getHeader(RequestLoggingFilter.HEADER)).isEqualTo("gateway-42.a_b:c");
    }

    @Test
    void replacesUnsafeIncomingRequestIds() throws Exception {
        for (String unsafe : new String[] {"fake\nERROR forged line", "x".repeat(65), "", "a b"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/things");
            request.addHeader(RequestLoggingFilter.HEADER, unsafe);
            assertThat(run(request, logging("x")).getHeader(RequestLoggingFilter.HEADER)).as(unsafe).matches(UUID_PATTERN);
        }
    }

    @Test
    void neverLogsTheQueryString(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/callback");
        request.setQueryString("code=s3cr3t-value");
        run(request, logging("x"));
        assertThat(output).contains("GET /callback -> 200").doesNotContain("s3cr3t-value");
    }

    @Test
    void logsFailuresWithTheRequestIdAndStackTrace(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/boom");
        request.addHeader(RequestLoggingFilter.HEADER, "req-boom-1");
        assertThatThrownBy(() -> run(request, (req, res) -> {
            throw new IllegalStateException("kaboom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(output).containsPattern("ERROR.*\\[req-boom-1 -\\].*POST /boom failed after \\d+ ms");
        assertThat(output).contains("java.lang.IllegalStateException: kaboom");
    }

    @Test
    void clearsTheRequestIdOnceTheRequestIsDone() throws Exception {
        run(new MockHttpServletRequest("GET", "/things"), logging("x"));
        assertThat(MDC.get(RequestLoggingFilter.MDC_KEY)).isNull();
    }
}
