package com.healthplatform.identity;

import com.healthplatform.common.crypto.FieldEncryptionAutoConfiguration;
import com.healthplatform.common.web.OpenApiConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

// identity-service only component-scans com.healthplatform.identity, so shared `common` beans
// need an explicit @Import (same reason SecurityConfig registers CorsProperties by hand).
// FieldEncryptionAutoConfiguration has to be imported because AuthService constructor-injects
// BlindIndexHasher, which Spring won't wire unless the bean is registered. Hibernate would
// still resolve EncryptedStringConverter on User.email without it.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@Import({OpenApiConfig.class, FieldEncryptionAutoConfiguration.class})
public class IdentityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
