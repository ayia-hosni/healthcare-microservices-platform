package com.healthplatform.doctor.service;

import com.healthplatform.doctor.domain.Department;
import com.healthplatform.doctor.domain.Doctor;
import com.healthplatform.doctor.event.DoctorEventPublisher;
import com.healthplatform.doctor.repository.DepartmentRepository;
import com.healthplatform.doctor.repository.DoctorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * Exercises the real @Cacheable AOP behavior on DoctorService.findBySpecialty, which a plain
 * Mockito unit test can't observe since the annotation only takes effect behind a
 * Spring-generated proxy.
 */
class DoctorServiceCacheTest {

    private AnnotationConfigApplicationContext context;
    private DoctorRepository doctorRepository;
    private DoctorService doctorService;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(CacheTestConfig.class);
        doctorRepository = context.getBean(DoctorRepository.class);
        doctorService = context.getBean(DoctorService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void secondLookupBySpecialtyIsServedFromCacheNotRepository() {
        Department cardiology = new Department("Cardiology");
        Doctor doctor = new Doctor(UUID.randomUUID(), "Amina", "Nasser", "Cardiology", cardiology);
        when(doctorRepository.findBySpecialtyIgnoreCase("Cardiology")).thenReturn(List.of(doctor));

        doctorService.findBySpecialty("Cardiology");
        doctorService.findBySpecialty("Cardiology");

        verify(doctorRepository, times(1)).findBySpecialtyIgnoreCase("Cardiology");
    }

    @Test
    void differentSpecialtyKeysAreCachedIndependently() {
        Department cardiology = new Department("Cardiology");
        Department neurology = new Department("Neurology");
        Doctor cardiologist = new Doctor(UUID.randomUUID(), "Amina", "Nasser", "Cardiology", cardiology);
        Doctor neurologist = new Doctor(UUID.randomUUID(), "Omar", "Farid", "Neurology", neurology);
        when(doctorRepository.findBySpecialtyIgnoreCase("Cardiology")).thenReturn(List.of(cardiologist));
        when(doctorRepository.findBySpecialtyIgnoreCase("Neurology")).thenReturn(List.of(neurologist));

        doctorService.findBySpecialty("Cardiology");
        doctorService.findBySpecialty("Neurology");

        verify(doctorRepository, times(1)).findBySpecialtyIgnoreCase("Cardiology");
        verify(doctorRepository, times(1)).findBySpecialtyIgnoreCase("Neurology");
    }

    @TestConfiguration
    @EnableCaching
    static class CacheTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("doctor-availability");
        }

        @Bean
        DoctorRepository doctorRepository() {
            return mock(DoctorRepository.class);
        }

        @Bean
        DepartmentRepository departmentRepository() {
            return mock(DepartmentRepository.class);
        }

        @Bean
        DoctorEventPublisher doctorEventPublisher() {
            return mock(DoctorEventPublisher.class);
        }

        @Bean
        DoctorService doctorService(DoctorRepository doctorRepository, DepartmentRepository departmentRepository,
                                     DoctorEventPublisher publisher) {
            return new DoctorService(doctorRepository, departmentRepository, publisher);
        }
    }
}
