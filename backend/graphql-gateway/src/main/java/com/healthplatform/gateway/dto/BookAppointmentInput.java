package com.healthplatform.gateway.dto;

/**
 * Mirrors appointment-service's BookAppointmentRequest — sent downstream as-is. Every field
 * is kept as a plain String, including patientId/doctorId (GraphQL ID! arguments already bind
 * to String with zero ambiguity) — Jackson on the appointment-service side parses the same
 * wire format straight into its UUID/Instant-typed fields when deserializing the POST body,
 * so there's nothing to gain by also parsing them on this side.
 */
public record BookAppointmentInput(
        String patientId,
        String doctorId,
        String scheduledStart,
        String scheduledEnd,
        String idempotencyKey
) {}
