package com.rbac_platform.lib.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Crypto settings (rbac.crypto.*).
 *
 * @param kek   base64 of the 32-byte key-encryption key; null while a service does not encrypt anything (no cipher bean)
 * @param kekId id stored with each ciphertext; change it when the KEK is rotated
 */
@ConfigurationProperties("rbac.crypto")
public record CryptoProperties(String kek, @DefaultValue("env-1") String kekId) {

    /** The generated toString would print the key into logs and error reports. */
    @Override
    public String toString() {
        return "CryptoProperties[kek=" + (kek == null ? "null" : "***") + ", kekId=" + kekId + "]";
    }
}
