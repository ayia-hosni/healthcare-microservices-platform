package com.healthplatform.billing.payer;

import com.healthplatform.billing.payer.soap.gen.CheckEligibilityRequest;
import com.healthplatform.billing.payer.soap.gen.CheckEligibilityResponse;
import org.springframework.ws.client.core.support.WebServiceGatewaySupport;

/**
 * Pure SOAP transport for the external payer's CheckEligibility operation — marshalling,
 * sending, and unmarshalling only. Timeouts and graceful degradation live one layer up in
 * EligibilityService (a resilience4j @CircuitBreaker there, not a manual try/catch here,
 * following the declarative pattern appointment-service already uses for its own
 * dependency guard — see AppointmentController's @RateLimiter). See
 * docs/adr/0003-soap-payer-eligibility-integration.md.
 */
public class PayerEligibilityClient extends WebServiceGatewaySupport {

    public CheckEligibilityResponse checkEligibility(CheckEligibilityRequest request) {
        return (CheckEligibilityResponse) getWebServiceTemplate()
                .marshalSendAndReceive(getDefaultUri(), request);
    }
}
