package com.rbac_platform.lib.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class EnvelopeCipherTest {

    private static final Kek KEK = Kek.fromBase64("env-1", Base64.getEncoder().encodeToString(new byte[32]));
    private final EnvelopeCipher cipher = new EnvelopeCipher(KEK);
    private final byte[] plaintext = "secret".getBytes(StandardCharsets.UTF_8);
    private final byte[] aad = "t1|users|7".getBytes(StandardCharsets.UTF_8);

    @Test
    void sealThenOpenReturnsPlaintext() {
        Sealed sealed = cipher.seal(plaintext, aad);
        assertThat(sealed.keyId()).isEqualTo("env-1");
        assertThat(cipher.open(sealed, aad)).isEqualTo(plaintext);
    }

    @Test
    void sameInputTwiceGivesDifferentCiphertext() {
        assertThat(cipher.seal(plaintext, aad).ciphertext()).isNotEqualTo(cipher.seal(plaintext, aad).ciphertext());
    }

    @Test
    void wrongAadFails() {
        Sealed sealed = cipher.seal(plaintext, aad);
        assertThatThrownBy(() -> cipher.open(sealed, "t2|users|7".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    void flippedByteFails() {
        byte[] ct = cipher.seal(plaintext, aad).ciphertext();
        ct[ct.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.open(new Sealed("env-1", ct), aad)).isInstanceOf(CryptoException.class);
    }

    @Test
    void foreignKeyIdFails() {
        byte[] ct = cipher.seal(plaintext, aad).ciphertext();
        assertThatThrownBy(() -> cipher.open(new Sealed("env-2", ct), aad)).isInstanceOf(CryptoException.class);
    }

    @Test
    void kekMustBe32Bytes() {
        String sixteen = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> Kek.fromBase64("env-1", sixteen))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void invalidBase64DoesNotLeakInputInAnyCauseMessage() {
        String bad = "abc?defSECRETghij";
        Throwable t = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Kek.fromBase64("env-1", bad));
        for (; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(bad).doesNotContain("?").doesNotContain("SECRET");
        }
    }
}
