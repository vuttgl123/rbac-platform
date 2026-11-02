package com.rbac_platform.lib.crypto;

/** A seal/open failed: tampered data, wrong AAD, or a ciphertext sealed under a different key. Never carries key material. */
public class CryptoException extends RuntimeException {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
