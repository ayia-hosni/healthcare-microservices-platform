package com.healthplatform.gateway.dto;

import java.util.UUID;

/**
 * Mirrors patient-service's PatientResponse — this gateway is a REST client, not a shared
 * module. dateOfBirth is left as the raw ISO-8601 String from the wire (not parsed into
 * LocalDate): graphql-java's default String scalar serializer returns null for arbitrary
 * objects like LocalDate rather than stringifying them, which would silently violate this
 * schema's `dateOfBirth: String!` non-null constraint. The gateway only relays this value, so
 * there's no need to parse it at all.
 */
public record PatientDto(
        UUID id,
        String firstName,
        String lastName,
        String dateOfBirth,
        String email,
        String phoneNumber
) {}
