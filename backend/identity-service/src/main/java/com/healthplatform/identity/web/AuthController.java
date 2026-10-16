package com.healthplatform.identity.web;

import com.healthplatform.identity.service.AuthService;
import com.healthplatform.identity.web.dto.AuthResponse;
import com.healthplatform.identity.web.dto.CreateStaffAccountRequest;
import com.healthplatform.identity.web.dto.LoginRequest;
import com.healthplatform.identity.web.dto.RefreshRequest;
import com.healthplatform.identity.web.dto.RegisterRequest;
import com.healthplatform.identity.web.dto.StaffAccountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Register, login, refresh, and logout")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "Create a new patient account")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/staff-accounts")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create an ADMIN/DOCTOR/NURSE/BILLING_CLERK account (admin-only; patients self-register instead)")
    public ResponseEntity<StaffAccountResponse> createStaffAccount(@Valid @RequestBody CreateStaffAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.createStaffAccount(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for an access/refresh token pair")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a valid refresh token for a new token pair (rotates the refresh token)")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke a refresh token")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
