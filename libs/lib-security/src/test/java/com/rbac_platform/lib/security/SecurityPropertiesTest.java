package com.rbac_platform.lib.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityPropertiesTest {

    private static SecurityProperties withPrefix(String prefix) {
        return new SecurityProperties(prefix, "rbac-api", List.of(), Duration.ofSeconds(30), "secret", null);
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
    void toStringHidesInternalToken() {
        assertThat(withPrefix("https://id.example.com/t/").toString()).doesNotContain("secret");
    }
}
