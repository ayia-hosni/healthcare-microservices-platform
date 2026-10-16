package com.healthplatform.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backs app.field-encryption.key (every service that stores PHI) and
 * app.field-encryption.hmac-key (identity-service only, for the login email blind index —
 * see BlindIndexHasher). Both are base64-encoded 32-byte AES-256 keys. Mirrors
 * JwtSecurityProperties's shape deliberately — same env-var-driven key pattern, different key.
 */
@ConfigurationProperties(prefix = "app.field-encryption")
public class FieldEncryptionProperties {
    private String key;
    private String hmacKey;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getHmacKey() { return hmacKey; }
    public void setHmacKey(String hmacKey) { this.hmacKey = hmacKey; }
}
