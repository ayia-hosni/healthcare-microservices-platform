package com.healthplatform.billing.payer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;

import java.time.Duration;

/**
 * Wires PayerEligibilityClient the standard Spring-WS way: a Jaxb2Marshaller bound to the
 * classes JAXB generated from payer-eligibility.xsd, and short connect/read timeouts so a
 * slow or unreachable payer fails fast into the circuit breaker in EligibilityService
 * rather than hanging a request thread. Short timeouts mirror
 * appointment-service's grpc/BookingValidationClient's 2s deadline for the same reason.
 */
@Configuration
public class PayerClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    public Jaxb2Marshaller payerMarshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("com.healthplatform.billing.payer.soap.gen");
        return marshaller;
    }

    @Bean
    public PayerEligibilityClient payerEligibilityClient(
            Jaxb2Marshaller payerMarshaller,
            @Value("${payer.eligibility.url}") String payerEligibilityUrl) {
        HttpUrlConnectionMessageSender messageSender = new HttpUrlConnectionMessageSender();
        messageSender.setConnectionTimeout(CONNECT_TIMEOUT);
        messageSender.setReadTimeout(READ_TIMEOUT);

        PayerEligibilityClient client = new PayerEligibilityClient();
        client.setDefaultUri(payerEligibilityUrl);
        client.setMarshaller(payerMarshaller);
        client.setUnmarshaller(payerMarshaller);
        client.setMessageSender(messageSender);
        return client;
    }
}
