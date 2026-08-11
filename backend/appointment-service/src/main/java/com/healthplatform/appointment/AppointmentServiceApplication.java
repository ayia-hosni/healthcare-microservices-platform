package com.healthplatform.appointment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Component/entity/repository scanning is widened to com.healthplatform.common so the shared
 * PlatformSecurityConfig, GlobalExceptionHandler, CorrelationIdFilter, and outbox mechanism
 * (OutboxWriter/OutboxRelay/OutboxEvent) are actually picked up — @SpringBootApplication alone
 * only scans this service's own package.
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {
        "com.healthplatform.appointment",
        "com.healthplatform.common.security",
        "com.healthplatform.common.web",
        "com.healthplatform.common.outbox"
})
@EntityScan(basePackages = {"com.healthplatform.appointment", "com.healthplatform.common.outbox"})
@EnableJpaRepositories(basePackages = {"com.healthplatform.appointment", "com.healthplatform.common.outbox"})
public class AppointmentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AppointmentServiceApplication.class, args);
    }
}
