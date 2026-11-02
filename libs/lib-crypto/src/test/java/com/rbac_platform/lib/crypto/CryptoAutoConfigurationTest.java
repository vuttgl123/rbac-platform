package com.rbac_platform.lib.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CryptoAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(CryptoAutoConfiguration.class));

    @Test
    void cipherOnlyWhenKekConfigured() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(PasswordHasher.class);
            assertThat(ctx).doesNotHaveBean(EnvelopeCipher.class);
        });
        runner.withPropertyValues("rbac.crypto.kek=" + Base64.getEncoder().encodeToString(new byte[32]))
                .run(ctx -> assertThat(ctx).hasSingleBean(EnvelopeCipher.class).hasSingleBean(PasswordHasher.class));
    }

    @Test
    void badKekFailsStartup() {
        runner.withPropertyValues("rbac.crypto.kek=" + Base64.getEncoder().encodeToString(new byte[10]))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    Throwable root = ctx.getStartupFailure();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root).hasMessageContaining("32 bytes");
                });
    }
}
