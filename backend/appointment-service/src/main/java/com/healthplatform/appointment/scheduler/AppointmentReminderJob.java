package com.healthplatform.appointment.scheduler;

import com.healthplatform.appointment.domain.Appointment;
import com.healthplatform.appointment.domain.AppointmentStatus;
import com.healthplatform.appointment.repository.AppointmentRepository;
import com.healthplatform.common.events.NotificationRequestedEvent;
import com.healthplatform.common.events.DomainEvent;
import com.healthplatform.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Publishes a NotificationRequestedEvent ~24h before each confirmed appointment.
 * Runs hourly (not more often) since a 24h reminder window tolerates an hour of jitter —
 * cheaper than a finer-grained schedule for no real benefit.
 */
@Component
public class AppointmentReminderJob {

    private static final Logger log = LoggerFactory.getLogger(AppointmentReminderJob.class);

    private final AppointmentRepository appointmentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public AppointmentReminderJob(AppointmentRepository appointmentRepository, KafkaTemplate<String, Object> kafkaTemplate) {
        this.appointmentRepository = appointmentRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(cron = "0 0 * * * *") // top of every hour
    @Transactional(readOnly = true)
    public void sendUpcomingReminders() {
        Instant windowStart = Instant.now().plus(23, ChronoUnit.HOURS);
        Instant windowEnd = Instant.now().plus(25, ChronoUnit.HOURS);

        List<Appointment> upcoming = appointmentRepository
                .findAllByStatusAndScheduledStartBetween(AppointmentStatus.CONFIRMED, windowStart, windowEnd);

        for (Appointment appt : upcoming) {
            var payload = new NotificationRequestedEvent(
                    appt.getPatientId().toString(), "EMAIL", "APPOINTMENT_REMINDER",
                    "{\"appointmentId\":\"" + appt.getId() + "\",\"scheduledStart\":\"" + appt.getScheduledStart() + "\"}"
            );
            var event = DomainEvent.of(appt.getId().toString(), "NotificationRequestedEvent", UUID.randomUUID().toString(), payload);
            kafkaTemplate.send(Topics.NOTIFICATION_REQUESTS, appt.getId().toString(), event);
        }
        log.info("Queued {} appointment reminders", upcoming.size());
    }
}
