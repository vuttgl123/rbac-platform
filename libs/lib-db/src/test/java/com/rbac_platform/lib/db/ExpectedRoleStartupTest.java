package com.rbac_platform.lib.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbac_platform.testsupport.AppDatabase;
import com.rbac_platform.testsupport.MigratedDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class ExpectedRoleStartupTest {

    private final MigratedDatabase db = AppDatabase.enableLogin("rbac_app");

    private SpringApplicationBuilder application(String... extraProperties) {
        return new SpringApplicationBuilder(DbTestApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.datasource.url=" + db.jdbcUrl(),
                        "spring.datasource.username=rbac_app",
                        "spring.datasource.password=unused-trust-auth")
                .properties(extraProperties);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Test
    void applicationDoesNotStartWithTheWrongRole() {
        assertThatThrownBy(() -> application("rbac.db.expected-role=authz_app").run())
                .satisfies(failure -> assertThat(rootCause(failure).getMessage())
                        .contains("expects database role 'authz_app'"));
    }

    @Test
    void applicationDoesNotStartWithoutAnExpectedRole() {
        assertThatThrownBy(() -> application().run())
                .satisfies(failure -> assertThat(rootCause(failure).getMessage())
                        .contains("rbac.db.expected-role"));
    }
}
