package com.healthplatform.billing.web.dto;

import java.math.BigDecimal;

public record EligibilityResponse(
        boolean eligible,
        String planName,
        BigDecimal copayAmount,
        String payerMessage
) {}
