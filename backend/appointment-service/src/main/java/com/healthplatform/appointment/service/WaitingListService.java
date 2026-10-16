package com.healthplatform.appointment.service;

import com.healthplatform.appointment.domain.WaitingListEntry;
import com.healthplatform.appointment.domain.WaitingListStatus;
import com.healthplatform.appointment.grpc.BookingValidationClient;
import com.healthplatform.appointment.repository.WaitingListRepository;
import com.healthplatform.appointment.web.dto.WaitingListEntryResponse;
import com.healthplatform.common.events.DomainEvent;
import com.healthplatform.common.events.NotificationRequestedEvent;
import com.healthplatform.common.events.Topics;
import com.healthplatform.common.exception.BusinessException;
import com.healthplatform.common.exception.ResourceNotFoundException;
import com.healthplatform.common.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The doctor waiting list: patients queue for a doctor, and when an appointment with that
 * doctor is cancelled, {@link #notifyNextForDoctor} hands the freed slot to the patient who
 * has waited longest (FIFO by {@code createdAt}).
 *
 * The "slot opened" message is enqueued through the {@link OutboxWriter}, not sent straight to
 * Kafka — {@code notifyNextForDoctor} runs inside {@link AppointmentService#cancel}'s
 * transaction, so the notification commits atomically with the cancellation. A crash between
 * the two can't leave the slot cancelled but the waiting patient un-notified.
 */
@Service
public class WaitingListService {

    private static final Logger log = LoggerFactory.getLogger(WaitingListService.class);

    private final WaitingListRepository waitingListRepository;
    private final BookingValidationClient bookingValidationClient;
    private final OutboxWriter outboxWriter;

    public WaitingListService(WaitingListRepository waitingListRepository,
                              BookingValidationClient bookingValidationClient,
                              OutboxWriter outboxWriter) {
        this.waitingListRepository = waitingListRepository;
        this.bookingValidationClient = bookingValidationClient;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public WaitingListEntryResponse join(UUID patientId, UUID doctorId) {
        bookingValidationClient.requirePatientExists(patientId);
        bookingValidationClient.requireDoctorExists(doctorId);

        // Also guarded by a partial unique index (V3 migration); checked here so the caller
        // gets a clean 422 instead of a constraint-violation 500 in the common case.
        if (waitingListRepository.existsByPatientIdAndDoctorIdAndStatus(patientId, doctorId, WaitingListStatus.WAITING)) {
            throw new BusinessException("ALREADY_ON_WAITING_LIST",
                    "This patient is already on the waiting list for this doctor");
        }

        WaitingListEntry entry = waitingListRepository.save(new WaitingListEntry(patientId, doctorId));
        return WaitingListEntryResponse.from(entry);
    }

    /**
     * Leave the queue. {@code patientScope} non-null restricts the caller to their own entries
     * (a PATIENT token); null means unrestricted (ADMIN/NURSE).
     */
    @Transactional
    public WaitingListEntryResponse leave(UUID entryId, UUID patientScope) {
        WaitingListEntry entry = waitingListRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Waiting-list entry not found: " + entryId));

        if (patientScope != null && !patientScope.equals(entry.getPatientId())) {
            throw new BusinessException("ACCESS_DENIED", "You can only leave the waiting list on your own behalf");
        }

        entry.cancel();
        return WaitingListEntryResponse.from(entry);
    }

    @Transactional(readOnly = true)
    public List<WaitingListEntryResponse> getForDoctor(UUID doctorId) {
        return waitingListRepository
                .findAllByDoctorIdAndStatusInOrderByCreatedAtAsc(doctorId,
                        List.of(WaitingListStatus.WAITING, WaitingListStatus.NOTIFIED))
                .stream().map(WaitingListEntryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<WaitingListEntryResponse> getForPatient(UUID patientId) {
        return waitingListRepository.findAllByPatientIdOrderByCreatedAtDesc(patientId)
                .stream().map(WaitingListEntryResponse::from).toList();
    }

    /**
     * A slot with {@code doctorId} just freed up (an appointment was cancelled). Notify the
     * patient who has waited longest, if any. Called from within {@link AppointmentService#cancel}'s
     * transaction — the outbox write below therefore commits with the cancellation.
     */
    @Transactional
    public Optional<WaitingListEntryResponse> notifyNextForDoctor(UUID doctorId, Instant freedSlotStart, Instant freedSlotEnd) {
        Optional<WaitingListEntry> next = waitingListRepository
                .findFirstByDoctorIdAndStatusOrderByCreatedAtAsc(doctorId, WaitingListStatus.WAITING);
        if (next.isEmpty()) {
            return Optional.empty();
        }

        WaitingListEntry entry = next.get();
        entry.markNotified();

        String payloadJson = String.format(
                "{\"waitingListEntryId\":\"%s\",\"doctorId\":\"%s\",\"slotStart\":\"%s\",\"slotEnd\":\"%s\"}",
                entry.getId(), doctorId, freedSlotStart, freedSlotEnd);

        var notification = new NotificationRequestedEvent(
                entry.getPatientId().toString(), "EMAIL", "WAITLIST_SLOT_OPEN", payloadJson);

        outboxWriter.enqueue(Topics.NOTIFICATION_REQUESTS, entry.getPatientId().toString(),
                DomainEvent.of(entry.getId().toString(), "NotificationRequestedEvent", MDC.get("correlationId"), notification));

        log.info("Notified waiting-list entry {} (patient {}) that a slot with doctor {} opened up",
                entry.getId(), entry.getPatientId(), doctorId);

        return Optional.of(WaitingListEntryResponse.from(entry));
    }
}
