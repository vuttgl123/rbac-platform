package com.rbac_platform.lib.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM under the KEK. The AAD binds a ciphertext to the row that holds it (tenant, table, id), so a value
 * copied to another row fails to open instead of decrypting silently.
 */
public class EnvelopeCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Kek kek;

    public EnvelopeCipher(Kek kek) {
        this.kek = kek;
    }

    public Sealed seal(byte[] plaintext, byte[] aad) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, kek.key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] out = new byte[NONCE_BYTES + encrypted.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
            System.arraycopy(encrypted, 0, out, NONCE_BYTES, encrypted.length);
            return new Sealed(kek.id(), out);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("seal failed", e);
        }
    }

    public byte[] open(Sealed sealed, byte[] aad) {
        if (!kek.id().equals(sealed.keyId())) {
            throw new CryptoException("sealed under key '" + sealed.keyId() + "' but the current key is '" + kek.id() + "'");
        }
        byte[] data = sealed.ciphertext();
        if (data.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new CryptoException("ciphertext too short");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, kek.key(), new GCMParameterSpec(TAG_BITS, Arrays.copyOf(data, NONCE_BYTES)));
            cipher.updateAAD(aad);
            return cipher.doFinal(data, NONCE_BYTES, data.length - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("open failed", e);
        }
    }
}
