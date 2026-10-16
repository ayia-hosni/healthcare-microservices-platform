package com.healthplatform.common.events;

import java.time.Instant;

/** Payload for the event published when a patient revokes previously granted consent. */
public record ConsentRevokedEvent(
        String patientId,
        Instant revokedAt
) {}
