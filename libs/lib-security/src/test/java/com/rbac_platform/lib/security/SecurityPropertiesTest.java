package com.rbac_platform.lib.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityPropertiesTest {

    private static SecurityProperties withPrefix(String prefix) {
        return new SecurityProperties(prefix, "rbac-api", List.of(), Duration.ofSeconds(30), "s3cr3t-".repeat(6), null);
    }

    @Test
    void issuerPrefixIsRequired() {
        assertThatThrownBy(() -> withPrefix(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rbac.security.issuer-prefix");
    }

    @Test
    void issuerPrefixMustEndWithTenantSegment() {
        assertThatThrownBy(() -> withPrefix("https://id.example.com/t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rbac.security.issuer-prefix");
    }

    @Test
    void internalTokenIsRequired() {
        assertThatThrownBy(() -> withToken(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rbac.security.internal-token");
    }

    @Test
    void internalTokenMustBeAtLeast32Characters() {
        assertThatThrownBy(() -> withToken("x".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rbac.security.internal-token");
        assertThat(withToken("x".repeat(32)).internalToken()).hasSize(32);
    }

    private static SecurityProperties withToken(String token) {
        return new SecurityProperties("https://id.example.com/t/", "rbac-api", List.of(), Duration.ofSeconds(30),
                token, null);
    }

    @Test
    void toStringHidesInternalToken() {
        assertThat(withPrefix("https://id.example.com/t/").toString()).doesNotContain("s3cr3t");
    }
}
