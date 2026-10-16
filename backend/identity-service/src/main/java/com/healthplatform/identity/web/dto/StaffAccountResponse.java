package com.healthplatform.identity.web.dto;

import com.healthplatform.identity.domain.Role;

import java.util.UUID;

public record StaffAccountResponse(
        UUID id,
        String email,
        Role role
) {}
