package com.healthplatform.identity.web.dto;

import com.healthplatform.identity.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Admin-only account creation (see AuthService's class comment on the elevated-role boundary). */
public record CreateStaffAccountRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 12, message = "password must be at least 12 characters") String password,
        @NotNull Role role
) {}
