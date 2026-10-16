package com.healthplatform.common.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Locale;

/**
 * Deterministic HMAC-SHA256 over a lowercase-normalized value — the opposite property from
 * AesGcmCipher on purpose. Used only where a value must be encrypted at rest AND still looked
 * up by exact match (identity-service's login email: findByEmailHash instead of findByEmail).
 * Same input always produces the same output, which is what makes it indexable — and exactly
 * why it's unsuitable for anything AesGcmCipher already handles (it leaks equality between
 * rows, which random-IV AES-GCM deliberately does not).
 *
 * Keyed with a SEPARATE key from the AES encryption key (app.field-encryption.hmac-key, not
 * .key) so that key isn't reused across two different cryptographic purposes.
 *
 * Registered via FieldEncryptionAutoConfiguration's @Bean methods (gated on hmac-key
 * specifically, not just .key, since only identity-service declares it), not a bare
 * @Component — see EncryptedStringConverter's javadoc for why.
 */
public class BlindIndexHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec keySpec;

    public BlindIndexHasher(FieldEncryptionProperties properties) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(properties.getHmacKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Field encryption HMAC key is not valid base64", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "Field encryption HMAC key must decode to exactly 32 bytes, got " + keyBytes.length);
        }
        this.keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    public String hash(String value) {
        if (value == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(keySpec);
            byte[] digest = mac.doFinal(value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute blind index hash", e);
        }
    }
}
