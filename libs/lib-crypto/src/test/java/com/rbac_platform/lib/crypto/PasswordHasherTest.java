package com.rbac_platform.lib.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashIsArgon2idWithOwaspParameters() {
        assertThat(hasher.hash("pw")).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    }

    @Test
    void verifyAcceptsRightAndRejectsWrong() {
        String hash = hasher.hash("pw");
        assertThat(hasher.verify("pw", hash)).isTrue();
        assertThat(hasher.verify("other", hash)).isFalse();
    }

    @Test
    void burnVerifyDoesNotThrow() {
        assertThatCode(() -> {
            hasher.burnVerify("anything");
            hasher.burnVerify("");
            hasher.burnVerify(null);
        }).doesNotThrowAnyException();
    }
}
