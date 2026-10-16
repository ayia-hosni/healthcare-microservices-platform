package com.healthplatform.appointment.web.dto;

import com.healthplatform.appointment.domain.WaitingListEntry;
import com.healthplatform.appointment.domain.WaitingListStatus;

import java.time.Instant;
import java.util.UUID;

public record WaitingListEntryResponse(
        UUID id,
        UUID patientId,
        UUID doctorId,
        WaitingListStatus status,
        Instant createdAt,
        Instant notifiedAt
) {
    public static WaitingListEntryResponse from(WaitingListEntry e) {
        return new WaitingListEntryResponse(
                e.getId(), e.getPatientId(), e.getDoctorId(), e.getStatus(), e.getCreatedAt(), e.getNotifiedAt());
    }
}
