package com.healthplatform.billing.payer;

import com.healthplatform.billing.payer.soap.gen.CheckEligibilityRequest;
import com.healthplatform.billing.payer.soap.gen.CheckEligibilityResponse;
import com.healthplatform.billing.web.dto.EligibilityRequest;
import com.healthplatform.billing.web.dto.EligibilityResponse;
import com.healthplatform.common.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Service;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.LocalDate;

/**
 * Orchestrates a call to the external payer: builds the SOAP request, calls
 * PayerEligibilityClient, maps the response back to our own DTO. The circuit breaker lives
 * here (declaratively, matching AppointmentController's @RateLimiter precedent) rather than
 * as manual try/catch in the client, so a payer outage degrades this one endpoint instead of
 * tying up request threads waiting on it.
 */
@Service
public class EligibilityService {

    private final PayerEligibilityClient payerEligibilityClient;

    public EligibilityService(PayerEligibilityClient payerEligibilityClient) {
        this.payerEligibilityClient = payerEligibilityClient;
    }

    @CircuitBreaker(name = "payerEligibility", fallbackMethod = "eligibilityUnavailable")
    public EligibilityResponse checkEligibility(EligibilityRequest request) {
        CheckEligibilityRequest soapRequest = new CheckEligibilityRequest();
        soapRequest.setMemberId(request.memberId());
        soapRequest.setPayerId(request.payerId());
        soapRequest.setDateOfBirth(toXmlDate(request.dateOfBirth()));

        CheckEligibilityResponse soapResponse = payerEligibilityClient.checkEligibility(soapRequest);

        return new EligibilityResponse(
                soapResponse.isEligible(),
                soapResponse.getPlanName(),
                soapResponse.getCopayAmount(),
                soapResponse.getPayerMessage());
    }

    // Invoked reflectively by resilience4j when payerEligibility's circuit is open or the
    // call throws (SOAP fault, timeout, connection refused — see PayerClientConfig's
    // timeouts) — surfaced as a business error, not a 500, matching
    // BookingValidationClient's call() wrapper in appointment-service.
    @SuppressWarnings("unused")
    private EligibilityResponse eligibilityUnavailable(EligibilityRequest request, Exception e) {
        throw new BusinessException("ELIGIBILITY_UNAVAILABLE",
                "Could not verify insurance eligibility right now: " + e.getMessage());
    }

    private static XMLGregorianCalendar toXmlDate(LocalDate date) {
        try {
            return DatatypeFactory.newInstance().newXMLGregorianCalendarDate(
                    date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
                    DatatypeConstants.FIELD_UNDEFINED);
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("JAXB DatatypeFactory unavailable", e);
        }
    }
}
