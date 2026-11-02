package com.rbac_platform.lib.crypto;

/**
 * An encrypted value and the id of the KEK that sealed it.
 *
 * @param keyId      id of the {@link Kek} used
 * @param ciphertext 12-byte random nonce followed by AES-256-GCM output (128-bit tag)
 */
public record Sealed(String keyId, byte[] ciphertext) {
}
