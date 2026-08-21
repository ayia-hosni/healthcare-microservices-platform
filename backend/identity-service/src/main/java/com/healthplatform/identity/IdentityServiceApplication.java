package com.healthplatform.identity;

import com.healthplatform.common.web.OpenApiConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

// identity-service scans only com.healthplatform.identity (see SecurityConfig, which registers
// CorsProperties explicitly for the same reason), so the shared OpenApiConfig bean needs an
// explicit @Import rather than relying on the @ComponentScan widening every other service uses.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@Import(OpenApiConfig.class)
public class IdentityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
