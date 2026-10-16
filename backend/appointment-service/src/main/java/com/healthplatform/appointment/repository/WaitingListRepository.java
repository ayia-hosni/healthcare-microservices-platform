package com.healthplatform.appointment.repository;

import com.healthplatform.appointment.domain.WaitingListEntry;
import com.healthplatform.appointment.domain.WaitingListStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitingListRepository extends JpaRepository<WaitingListEntry, UUID> {

    boolean existsByPatientIdAndDoctorIdAndStatus(UUID patientId, UUID doctorId, WaitingListStatus status);

    /**
     * FIFO head of a doctor's queue. Pessimistic-locked: a cancellation frees one slot, so two
     * concurrent cancellations for the same doctor must not both hand it to the same waiting
     * patient. The lock serializes them; the second sees the first's NOTIFIED transition and
     * moves on to the next WAITING entry.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WaitingListEntry> findFirstByDoctorIdAndStatusOrderByCreatedAtAsc(UUID doctorId, WaitingListStatus status);

    List<WaitingListEntry> findAllByDoctorIdAndStatusInOrderByCreatedAtAsc(UUID doctorId, List<WaitingListStatus> statuses);

    List<WaitingListEntry> findAllByPatientIdOrderByCreatedAtDesc(UUID patientId);

    List<WaitingListEntry> findAllByStatusAndNotifiedAtBefore(WaitingListStatus status, Instant cutoff);
}
