package com.healthplatform.doctor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@ComponentScan(basePackages = {
        "com.healthplatform.doctor",
        "com.healthplatform.common.security",
        "com.healthplatform.common.web",
        "com.healthplatform.common.outbox"
})
@EntityScan(basePackages = {"com.healthplatform.doctor", "com.healthplatform.common.outbox"})
@EnableJpaRepositories(basePackages = {"com.healthplatform.doctor", "com.healthplatform.common.outbox"})
public class DoctorServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DoctorServiceApplication.class, args);
    }
}
