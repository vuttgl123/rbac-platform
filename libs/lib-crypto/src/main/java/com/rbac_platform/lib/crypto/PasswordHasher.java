package com.rbac_platform.lib.crypto;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** Argon2id with the OWASP minimum (19 MiB, 2 iterations, 1 lane); salt and parameters live in the PHC string. */
public class PasswordHasher {

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    private final String dummyHash = encoder.encode("burn-verify-dummy");

    public String hash(CharSequence raw) {
        return encoder.encode(raw);
    }

    public boolean verify(CharSequence raw, String phc) {
        return encoder.matches(raw, phc);
    }

    /**
     * Spends the cost of a real verify against a fixed hash with the same parameters, so an unknown user cannot be
     * told apart from a wrong password by timing. Never throws.
     */
    public void burnVerify(CharSequence raw) {
        try {
            encoder.matches(raw == null ? "" : raw, dummyHash);
        } catch (RuntimeException ignored) {
            // timing equaliser only; the result is irrelevant
        }
    }
}
