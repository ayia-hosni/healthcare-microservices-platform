package com.healthplatform.common.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the PHI-encryption beans conditionally, via @Bean methods — the standard, reliable
 * Spring Boot idiom for "only activate if configured" (the same pattern this codebase already
 * uses for RedisCacheConfig's @ConditionalOnClass). This gating is required, not decorative:
 * `common` (and therefore every class in this package) is on every service's classpath, but
 * only patient-service/emr-service/identity-service declare app.field-encryption.key — without
 * it, these beans would try to construct in doctor-service, appointment-service, and every
 * other service with no PHI columns and no reason to need this key configured at all.
 *
 * A bare @Component + class-level @ConditionalOnProperty on each converter was tried first and
 * discarded: it turned out to be unreliable for classes resolved through non-standard paths.
 * Hibernate's SpringBeanContainer picked up EncryptedStringConverter/EncryptedLocalDateConverter
 * by type regardless of the condition, while BlindIndexHasher — consumed via ordinary
 * constructor injection in AuthService — was correctly skipped and crashed identity-service at
 * startup with a missing-bean error. @Bean-method-level conditions don't have this gap.
 */
@Configuration
public class FieldEncryptionAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.field-encryption", name = "key")
    public EncryptedStringConverter encryptedStringConverter(FieldEncryptionProperties properties) {
        return new EncryptedStringConverter(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.field-encryption", name = "key")
    public EncryptedLocalDateConverter encryptedLocalDateConverter(FieldEncryptionProperties properties) {
        return new EncryptedLocalDateConverter(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.field-encryption", name = "hmac-key")
    public BlindIndexHasher blindIndexHasher(FieldEncryptionProperties properties) {
        return new BlindIndexHasher(properties);
    }
}
