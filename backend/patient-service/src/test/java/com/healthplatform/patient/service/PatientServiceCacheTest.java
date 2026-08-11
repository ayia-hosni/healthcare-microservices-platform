package com.healthplatform.patient.service;

import com.healthplatform.patient.domain.Patient;
import com.healthplatform.patient.event.PatientEventPublisher;
import com.healthplatform.patient.repository.PatientRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the real @Cacheable/@CacheEvict AOP behavior on PatientService.getById /
 * updatePhoneNumber, which a plain Mockito unit test can't observe since the annotations
 * only take effect behind a Spring-generated proxy.
 */
class PatientServiceCacheTest {

    private AnnotationConfigApplicationContext context;
    private PatientRepository patientRepository;
    private PatientService patientService;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(CacheTestConfig.class);
        patientRepository = context.getBean(PatientRepository.class);
        patientService = context.getBean(PatientService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void secondLookupIsServedFromCacheNotRepository() {
        UUID id = UUID.randomUUID();
        Patient patient = new Patient(id, "Aya", "Hosni", LocalDate.of(1990, 5, 1), "aya@example.com");
        when(patientRepository.findById(id)).thenReturn(Optional.of(patient));

        patientService.getById(id);
        patientService.getById(id);

        verify(patientRepository, times(1)).findById(id);
    }

    @Test
    void updatePhoneNumberEvictsCacheSoNextLookupHitsRepositoryAgain() {
        UUID id = UUID.randomUUID();
        Patient patient = new Patient(id, "Aya", "Hosni", LocalDate.of(1990, 5, 1), "aya@example.com");
        when(patientRepository.findById(id)).thenReturn(Optional.of(patient));

        patientService.getById(id);
        patientService.updatePhoneNumber(id, "555-0000");
        patientService.getById(id);

        verify(patientRepository, times(2)).findById(id);
    }

    @TestConfiguration
    @EnableCaching
    static class CacheTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("patients");
        }

        @Bean
        PatientRepository patientRepository() {
            return mock(PatientRepository.class);
        }

        @Bean
        PatientEventPublisher patientEventPublisher() {
            return mock(PatientEventPublisher.class);
        }

        @Bean
        PatientService patientService(PatientRepository repository, PatientEventPublisher publisher) {
            return new PatientService(repository, publisher);
        }
    }
}
