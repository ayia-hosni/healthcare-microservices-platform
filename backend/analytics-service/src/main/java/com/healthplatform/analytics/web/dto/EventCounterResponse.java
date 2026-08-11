package com.healthplatform.analytics.web.dto;

import java.time.LocalDate;
import java.util.UUID;

public record EventCounterResponse(
        UUID id,
        String eventType,
        LocalDate counterDate,
        long count
) {}
