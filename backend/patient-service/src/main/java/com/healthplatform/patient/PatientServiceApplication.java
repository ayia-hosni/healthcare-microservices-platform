package com.healthplatform.patient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
// Mirror the exclude filters @SpringBootApplication's own scan uses — without them this
// explicit @ComponentScan would pull test-only @TestConfiguration classes (e.g. a *CacheTest's
// nested config) off the test classpath and collide with the real beans.
@ComponentScan(basePackages = {
        "com.healthplatform.patient",
        "com.healthplatform.common.security",
        "com.healthplatform.common.web",
        "com.healthplatform.common.outbox",
        "com.healthplatform.common.cache",
        "com.healthplatform.common.crypto"
}, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
@EntityScan(basePackages = {"com.healthplatform.patient", "com.healthplatform.common.outbox"})
@EnableJpaRepositories(basePackages = {"com.healthplatform.patient", "com.healthplatform.common.outbox"})
public class PatientServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PatientServiceApplication.class, args);
    }
}
