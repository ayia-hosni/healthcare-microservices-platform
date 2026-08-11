package com.healthplatform.identity.web.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresInSeconds
) {}
