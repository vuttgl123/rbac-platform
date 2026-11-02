package com.rbac_platform.lib.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretsTest {

    private static final String P = "some.property";

    private static void rejected(String value) {
        assertThatThrownBy(() -> Secrets.requireSecret(P, value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(P);
    }

    @Test
    void acceptsAndReturnsValidSecret() {
        assertThat(Secrets.requireSecret(P, "s".repeat(32))).isEqualTo("s".repeat(32));
        assertThat(Secrets.requireSecret(P, "s".repeat(64))).hasSize(64);
    }

    @Test
    void rejectsUnresolvedPlaceholderEvenWhenLongEnough() {
        String placeholder = "${AUDIT_INTERNAL_TOKEN_SECRET_VALUE}";
        assertThat(placeholder.length()).isGreaterThanOrEqualTo(32);
        rejected(placeholder);
    }

    @Test
    void rejectsPaddedBlankNullAndShort() {
        rejected(" " + "s".repeat(32));
        rejected("s".repeat(32) + "\n");
        rejected(" ".repeat(40));
        rejected("");
        rejected(null);
        rejected("s".repeat(31));
    }

    @Test
    void messageDoesNotEchoTheValue() {
        assertThatThrownBy(() -> Secrets.requireSecret(P, "short-secret-value"))
                .hasMessageNotContaining("short-secret-value");
    }
}
