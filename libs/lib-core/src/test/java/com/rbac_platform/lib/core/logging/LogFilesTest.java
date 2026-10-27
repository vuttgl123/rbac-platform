package com.rbac_platform.lib.core.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.lib.core.CoreTestApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;

/** logback-spring.xml: every line goes to the main file, ERROR lines also to the error file, both via async appenders. */
@SpringBootTest(classes = CoreTestApplication.class, properties = "spring.application.name=rbac-platform")
class LogFilesTest {

    private static final Path MAIN = Path.of("target/test-logs/rbac-platform.log");
    private static final Path ERRORS = Path.of("target/test-logs/rbac-platform-error.log");

    private static final Logger log = LoggerFactory.getLogger(LogFilesTest.class);

    private static String read(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    /** Async appenders write on a background thread; wait until the text shows up. */
    private static String awaitContains(Path file, String text) throws Exception {
        for (int i = 0; i < 100; i++) {
            String content = read(file);
            if (content.contains(text)) {
                return content;
            }
            Thread.sleep(50);
        }
        return read(file);
    }

    @Test
    void infoGoesToTheMainFileOnlyAndErrorsAlsoToTheErrorFileWithTheirRequestId() throws Exception {
        String marker = UUID.randomUUID().toString();
        MDC.put(RequestLoggingFilter.MDC_KEY, "req-" + marker);
        try {
            log.info("info-{}", marker);
            log.error("error-{}", marker, new IllegalStateException("broken-" + marker));
        } finally {
            MDC.remove(RequestLoggingFilter.MDC_KEY);
        }

        String main = awaitContains(MAIN, "error-" + marker);
        assertThat(main).containsPattern("INFO .* \\[rbac-platform\\] .*\\[req-" + marker + " -\\] \\S*LogFilesTest +: info-" + marker);
        assertThat(main).contains("error-" + marker);

        String errors = awaitContains(ERRORS, "broken-" + marker);
        assertThat(errors).containsPattern("ERROR .*\\[req-" + marker + " -\\].*error-" + marker);
        assertThat(errors).contains("java.lang.IllegalStateException: broken-" + marker);
        assertThat(errors).doesNotContain("info-" + marker);
    }
}
