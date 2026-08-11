package com.healthplatform.doctor.web.dto;

import java.util.UUID;

public record DoctorResponse(
        UUID id,
        String firstName,
        String lastName,
        String specialty,
        String departmentName
) {}
