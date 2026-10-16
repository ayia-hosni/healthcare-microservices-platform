package com.healthplatform.common.events;

import java.time.Instant;

/** Payload for the event published when a patient grants consent for care/data use. */
public record ConsentGrantedEvent(
        String patientId,
        Instant grantedAt
) {}
