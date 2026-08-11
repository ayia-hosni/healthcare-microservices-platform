package com.healthplatform.emr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {
        "com.healthplatform.emr",
        "com.healthplatform.common.security",
        "com.healthplatform.common.web",
        "com.healthplatform.common.outbox"
})
@EntityScan(basePackages = {"com.healthplatform.emr", "com.healthplatform.common.outbox"})
@EnableJpaRepositories(basePackages = {"com.healthplatform.emr", "com.healthplatform.common.outbox"})
public class EmrServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EmrServiceApplication.class, args);
    }
}
