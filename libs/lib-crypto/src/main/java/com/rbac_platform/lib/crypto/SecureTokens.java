package com.rbac_platform.lib.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Opaque bearer tokens and the SHA-256 digests stored in their place (token_hash, key_hash, code_hash columns). */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MIN_BYTES = 32;

    private SecureTokens() {
    }

    /** Base64url without padding; at least 32 bytes (256 bits) of entropy is required. */
    public static String randomToken(int bytes) {
        if (bytes < MIN_BYTES) {
            throw new IllegalArgumentException("token needs at least " + MIN_BYTES + " bytes but was " + bytes);
        }
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Lower-case hex SHA-256 (64 chars) of the UTF-8 value. */
    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
