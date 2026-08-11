package com.healthplatform.common.dto;

import java.time.Instant;
import java.util.List;

/** Standard error body returned by every service's global exception handler. */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<String> details
) {}
