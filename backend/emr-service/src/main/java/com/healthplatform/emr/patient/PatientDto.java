package com.healthplatform.emr.patient;

import java.util.UUID;

/**
 * Mirrors patient-service's PatientResponse — emr-service is a REST client of patient-service,
 * not a shared module (same reasoning as graphql-gateway's identical PatientDto). dateOfBirth
 * stays the raw ISO-8601 "yyyy-MM-dd" string from the wire rather than a parsed LocalDate:
 * PatientFhirMapper feeds it straight into HAPI's DateType(String) constructor, which parses
 * that exact format itself.
 */
public record PatientDto(
        UUID id,
        String firstName,
        String lastName,
        String dateOfBirth,
        String email,
        String phoneNumber
) {}
