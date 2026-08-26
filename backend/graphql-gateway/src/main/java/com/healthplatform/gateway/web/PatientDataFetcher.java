package com.healthplatform.gateway.web;

import com.healthplatform.gateway.dto.PatientDto;
import com.healthplatform.gateway.grpc.PatientLookupGrpcClient;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
public class PatientDataFetcher {

    private final PatientLookupGrpcClient patientLookupGrpcClient;

    public PatientDataFetcher(PatientLookupGrpcClient patientLookupGrpcClient) {
        this.patientLookupGrpcClient = patientLookupGrpcClient;
    }

    @QueryMapping
    public PatientDto patient(@Argument String id) {
        return patientLookupGrpcClient.fetch(id);
    }
}
