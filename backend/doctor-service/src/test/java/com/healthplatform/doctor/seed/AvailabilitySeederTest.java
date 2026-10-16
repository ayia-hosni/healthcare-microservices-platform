package com.healthplatform.doctor.seed;

import com.healthplatform.doctor.domain.AvailabilitySlot;
import com.healthplatform.doctor.domain.Department;
import com.healthplatform.doctor.domain.Doctor;
import com.healthplatform.doctor.repository.DoctorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AvailabilitySeederTest {

    private DoctorRepository doctorRepository;
    private AvailabilitySeeder seeder;

    @BeforeEach
    void setUp() {
        doctorRepository = mock(DoctorRepository.class);
        seeder = new AvailabilitySeeder(doctorRepository);
    }

    @Test
    void backfillsMondayToFridayNineToFiveForADoctorWithNoAvailability() {
        Doctor doctor = new Doctor(UUID.randomUUID(), "Elena", "Vasquez", "Family Medicine", new Department("Primary Care"));
        when(doctorRepository.findAll()).thenReturn(List.of(doctor));

        seeder.run(null);

        assertThat(doctor.getAvailability()).hasSize(5);
        assertThat(doctor.getAvailability())
                .extracting(AvailabilitySlot::getDayOfWeek)
                .containsExactlyInAnyOrder(
                        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        assertThat(doctor.getAvailability())
                .allSatisfy(slot -> {
                    assertThat(slot.getStartTime()).isEqualTo(LocalTime.of(9, 0));
                    assertThat(slot.getEndTime()).isEqualTo(LocalTime.of(17, 0));
                });
    }

    @Test
    void leavesADoctorWithExistingAvailabilityUntouched() {
        Doctor doctor = new Doctor(UUID.randomUUID(), "Amina", "Nasser", "Cardiology", new Department("Cardiology"));
        doctor.addAvailabilitySlot(new AvailabilitySlot(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0)));
        when(doctorRepository.findAll()).thenReturn(List.of(doctor));

        seeder.run(null);

        assertThat(doctor.getAvailability()).hasSize(1);
    }

    @Test
    void onlySeedsDoctorsThatActuallyHaveNoAvailability() {
        Doctor configured = new Doctor(UUID.randomUUID(), "Amina", "Nasser", "Cardiology", new Department("Cardiology"));
        configured.addAvailabilitySlot(new AvailabilitySlot(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0)));
        Doctor unconfigured = new Doctor(UUID.randomUUID(), "Elena", "Vasquez", "Family Medicine", new Department("Primary Care"));
        when(doctorRepository.findAll()).thenReturn(List.of(configured, unconfigured));

        seeder.run(null);

        assertThat(configured.getAvailability()).hasSize(1);
        assertThat(unconfigured.getAvailability()).hasSize(5);
    }
}
