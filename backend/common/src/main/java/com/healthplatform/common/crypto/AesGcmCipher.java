package com.healthplatform.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encrypt/decrypt for PHI columns. Storage format is
 * Base64(IV[12 bytes] || ciphertext || GCM tag[16 bytes appended by the JCE provider]) — a
 * single opaque string, so the JPA converters that use this can map cleanly onto one TEXT
 * column with no separate IV column to manage.
 *
 * A fresh random IV is generated per encrypt() call (never reused), which is what makes this
 * nondeterministic: encrypting the same plaintext twice produces different ciphertext both
 * times. That's required for names/DOB/clinical text — deterministic encryption would leak
 * equality (two patients with the same first name would have identical ciphertext), which is
 * exactly the kind of side channel field-level encryption is supposed to close. Where
 * deterministic, searchable behavior IS wanted (identity-service's login email), that's a
 * SEPARATE mechanism — see BlindIndexHasher — not this class.
 */
public class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKeySpec keySpec;

    public AesGcmCipher(String base64Key) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Field encryption key is not valid base64", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "Field encryption key must decode to exactly 32 bytes for AES-256, got " + keyBytes.length);
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt field value", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            if (combined.length < IV_LENGTH_BYTES) {
                throw new IllegalStateException("Encrypted field value is shorter than the IV alone — corrupt data");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Failed to decrypt field value — wrong key, or data written before encryption was enabled "
                    + "(see the rollout note on the corresponding Flyway migration)", e);
        }
    }
}
