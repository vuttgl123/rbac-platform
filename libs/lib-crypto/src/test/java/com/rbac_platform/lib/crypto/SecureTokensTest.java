package com.rbac_platform.lib.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecureTokensTest {

    @Test
    void tokenHas256BitsAndIsUrlSafe() {
        String token = SecureTokens.randomToken(32);
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void shortTokensAreRefused() {
        assertThatThrownBy(() -> SecureTokens.randomToken(16)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sha256HexKnownVector() {
        assertThat(SecureTokens.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
