package com.healthplatform.doctor.seed;

import com.healthplatform.doctor.domain.AvailabilitySlot;
import com.healthplatform.doctor.domain.Doctor;
import com.healthplatform.doctor.repository.DoctorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * Backfills standard Mon-Fri 09:00-17:00 (UTC) working hours for any doctor that doesn't have
 * a single AvailabilitySlot yet — infra/seed/seed.sh creates doctors but never their
 * availability, so every doctor is otherwise unbookable through the real "Schedule Check"
 * (BookingValidationClient.requireDoctorAvailable) until someone configures hours by hand via
 * DoctorController's availability endpoints.
 *
 * Off by default (app.seed.availability.enabled) since this is dev/demo convenience, not
 * something that belongs running against a real deployment — see docker-compose.yml, which
 * turns it on for local development only.
 *
 * Idempotent by construction rather than by tracking "have I run before": it only touches
 * doctors with zero existing slots, so re-running on every restart never duplicates or
 * clobbers hours someone (or a previous run) already set.
 */
@Component
@ConditionalOnProperty(prefix = "app.seed.availability", name = "enabled", havingValue = "true")
public class AvailabilitySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AvailabilitySeeder.class);

    private static final List<DayOfWeek> WORKDAYS = List.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
    private static final LocalTime START = LocalTime.of(9, 0);
    private static final LocalTime END = LocalTime.of(17, 0);

    private final DoctorRepository doctorRepository;

    public AvailabilitySeeder(DoctorRepository doctorRepository) {
        this.doctorRepository = doctorRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Doctor> doctors = doctorRepository.findAll();
        int seeded = 0;

        for (Doctor doctor : doctors) {
            if (!doctor.getAvailability().isEmpty()) {
                continue; // already configured (by this seeder before, or by hand) — leave it alone
            }
            for (DayOfWeek day : WORKDAYS) {
                doctor.addAvailabilitySlot(new AvailabilitySlot(day, START, END));
            }
            seeded++;
        }

        if (seeded > 0) {
            log.info("Seeded Mon-Fri 09:00-17:00 availability for {} doctor(s) with no existing hours", seeded);
        } else {
            log.info("Availability seeding skipped: all {} doctor(s) already have working hours configured", doctors.size());
        }
    }
}
