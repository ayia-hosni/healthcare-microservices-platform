package com.healthplatform.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Picks up PlatformSecurityConfig from common even though this service has no controllers of
 * its own: spring-boot-starter-security is already on the classpath transitively via `common`,
 * so without an explicit SecurityFilterChain bean, Spring Boot's default security auto-config
 * would otherwise kick in — HTTP Basic auth with a random per-boot password — locking Prometheus
 * and the Docker HEALTHCHECK out of /actuator/**.
 */
@SpringBootApplication
@ComponentScan(basePackages = {
        "com.healthplatform.notification",
        "com.healthplatform.common.security",
        "com.healthplatform.common.web"
})
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
