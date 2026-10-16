package com.healthplatform.common.crypto;

import jakarta.persistence.AttributeConverter;

/**
 * Applied explicitly per-field via @Convert(converter = EncryptedStringConverter.class) —
 * NOT autoApply, since most String columns on this platform (statuses, ids-as-strings, enum
 * names) are not PHI and must stay queryable/loggable in plaintext.
 *
 * Registered as a bean (not annotated @Component here — see FieldEncryptionAutoConfiguration)
 * so Spring Boot's JPA autoconfiguration can resolve it via Hibernate's
 * hibernate.resource.beans.container (SpringBeanContainer, wired automatically whenever the
 * EntityManagerFactory is built the standard Boot way — every service here does) instead of a
 * no-arg constructor, letting its AesGcmCipher/FieldEncryptionProperties dependency be
 * injected normally.
 *
 * Deliberately NOT a bare @Component with a class-level @ConditionalOnProperty: that
 * combination was tried first and turned out to be unreliable in practice — Hibernate's
 * SpringBeanContainer resolved this converter by type through a lookup path that bypassed the
 * condition, while a sibling class using the identical pattern (BlindIndexHasher, consumed via
 * plain constructor injection in AuthService) was correctly skipped and blew up
 * identity-service at startup with a missing-bean error. Gating via @Bean methods in
 * FieldEncryptionAutoConfiguration is the standard, always-reliable Spring Boot idiom for
 * conditional bean registration — see that class for why the gate is needed at all.
 */
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final AesGcmCipher cipher;

    public EncryptedStringConverter(FieldEncryptionProperties properties) {
        this.cipher = new AesGcmCipher(properties.getKey());
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData);
    }
}
