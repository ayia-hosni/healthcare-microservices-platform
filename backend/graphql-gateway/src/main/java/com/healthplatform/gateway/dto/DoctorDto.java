package com.healthplatform.gateway.dto;

import java.util.UUID;

/** Mirrors doctor-service's DoctorResponse — this gateway is a REST client, not a shared module. */
public record DoctorDto(
        UUID id,
        String firstName,
        String lastName,
        String specialty,
        String departmentName
) {}
