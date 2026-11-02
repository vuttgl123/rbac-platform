package com.rbac_platform.lib.crypto;

import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The key-encryption key that protects secrets at rest (TOTP secrets, signing private keys).
 *
 * @param id  stored next to each ciphertext so a rotated KEK can be recognised
 * @param key AES-256 key
 */
public record Kek(String id, SecretKey key) {

    static final int KEY_BYTES = 32;

    /** Decodes a standard base64 key; anything other than exactly 32 bytes is refused so a typo fails at startup. */
    public static Kek fromBase64(String id, String base64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("rbac.crypto.kek must be valid base64", e);
        }
        try {
            if (raw.length != KEY_BYTES) {
                throw new IllegalArgumentException("rbac.crypto.kek must decode to 32 bytes but was " + raw.length);
            }
            return new Kek(id, new SecretKeySpec(raw, "AES"));
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }
}
