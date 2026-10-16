package com.healthplatform.common.crypto;

import jakarta.persistence.AttributeConverter;

import java.time.LocalDate;

/**
 * Same shape as EncryptedStringConverter, for LocalDate columns — a date of birth is PHI on
 * its own even without a name attached to it. The underlying column must be TEXT, not DATE:
 * ciphertext is opaque base64, not a value Postgres can parse as a date.
 *
 * See EncryptedStringConverter's javadoc for why this is registered via
 * FieldEncryptionAutoConfiguration's @Bean methods rather than a bare @Component.
 */
public class EncryptedLocalDateConverter implements AttributeConverter<LocalDate, String> {

    private final AesGcmCipher cipher;

    public EncryptedLocalDateConverter(FieldEncryptionProperties properties) {
        this.cipher = new AesGcmCipher(properties.getKey());
    }

    @Override
    public String convertToDatabaseColumn(LocalDate attribute) {
        return attribute == null ? null : cipher.encrypt(attribute.toString());
    }

    @Override
    public LocalDate convertToEntityAttribute(String dbData) {
        String decrypted = cipher.decrypt(dbData);
        return decrypted == null ? null : LocalDate.parse(decrypted);
    }
}
