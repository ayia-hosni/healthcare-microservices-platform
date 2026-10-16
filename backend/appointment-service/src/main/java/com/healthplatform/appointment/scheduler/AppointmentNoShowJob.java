package com.healthplatform.appointment.scheduler;

import com.healthplatform.appointment.domain.Appointment;
import com.healthplatform.appointment.domain.AppointmentStatus;
import com.healthplatform.appointment.event.AppointmentEventPublisher;
import com.healthplatform.appointment.repository.AppointmentRepository;
import com.healthplatform.common.events.AppointmentNoShowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Marks an appointment NO_SHOW once it's a grace period past its start time and still
 * SCHEDULED (patient never checked in). This changes appointment state, not just fires a
 * notification, so it publishes through the outbox like book/cancel/reschedule rather than
 * straight to Kafka the way AppointmentReminderJob does.
 */
@Component
public class AppointmentNoShowJob {

    private static final Logger log = LoggerFactory.getLogger(AppointmentNoShowJob.class);
    private static final long GRACE_PERIOD_MINUTES = 30;

    private final AppointmentRepository appointmentRepository;
    private final AppointmentEventPublisher eventPublisher;

    public AppointmentNoShowJob(AppointmentRepository appointmentRepository, AppointmentEventPublisher eventPublisher) {
        this.appointmentRepository = appointmentRepository;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(cron = "0 0 * * * *") // top of every hour, same cadence as AppointmentReminderJob
    @Transactional
    public void markPastDueAppointmentsAsNoShow() {
        Instant cutoff = Instant.now().minus(GRACE_PERIOD_MINUTES, ChronoUnit.MINUTES);
        List<Appointment> overdue = appointmentRepository
                .findAllByStatusAndScheduledStartBefore(AppointmentStatus.SCHEDULED, cutoff);

        for (Appointment appointment : overdue) {
            appointment.markNoShow();
            eventPublisher.publishNoShow(
                    appointment.getId().toString(),
                    new AppointmentNoShowEvent(appointment.getId().toString(),
                            appointment.getPatientId().toString(), appointment.getDoctorId().toString()),
                    UUID.randomUUID().toString()
            );
        }
        log.info("Marked {} appointments as no-show", overdue.size());
    }
}
