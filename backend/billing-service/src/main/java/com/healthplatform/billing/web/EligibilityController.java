package com.healthplatform.billing.web;

import com.healthplatform.billing.payer.EligibilityService;
import com.healthplatform.billing.web.dto.EligibilityRequest;
import com.healthplatform.billing.web.dto.EligibilityResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand only — deliberately not wired into AppointmentEventConsumer's Kafka-driven
 * invoice generation, so a slow or down external payer can never block or retry-storm that
 * consumer. See docs/adr/0003-soap-payer-eligibility-integration.md.
 */
@RestController
@RequestMapping("/api/v1/billing/eligibility")
@Tag(name = "Billing")
public class EligibilityController {

    private final EligibilityService eligibilityService;

    public EligibilityController(EligibilityService eligibilityService) {
        this.eligibilityService = eligibilityService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','BILLING_CLERK','PATIENT')")
    public ResponseEntity<EligibilityResponse> check(@Valid @RequestBody EligibilityRequest request) {
        return ResponseEntity.ok(eligibilityService.checkEligibility(request));
    }
}
