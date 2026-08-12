package com.healthplatform.gateway.web;

import com.healthplatform.gateway.config.AuthHeaderInterceptor;
import com.healthplatform.gateway.dto.InvoiceDto;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Controller
public class InvoiceDataFetcher {

    private final RestClient billingServiceClient;

    public InvoiceDataFetcher(RestClient billingServiceClient) {
        this.billingServiceClient = billingServiceClient;
    }

    @QueryMapping
    public InvoiceDto invoice(@Argument String id,
                               @ContextValue(name = AuthHeaderInterceptor.AUTHORIZATION_CONTEXT_KEY, required = false) String authorization) {
        try {
            return billingServiceClient.get()
                    .uri("/api/v1/invoices/{id}", id)
                    .headers(headers -> DownstreamAuth.addTo(headers, authorization))
                    .retrieve()
                    .body(InvoiceDto.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }
}
