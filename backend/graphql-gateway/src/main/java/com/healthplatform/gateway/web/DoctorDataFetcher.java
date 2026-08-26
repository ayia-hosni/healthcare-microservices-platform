package com.healthplatform.gateway.web;

import com.healthplatform.gateway.dto.DoctorDto;
import com.healthplatform.gateway.grpc.DoctorLookupGrpcClient;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
public class DoctorDataFetcher {

    private final DoctorLookupGrpcClient doctorLookupGrpcClient;

    public DoctorDataFetcher(DoctorLookupGrpcClient doctorLookupGrpcClient) {
        this.doctorLookupGrpcClient = doctorLookupGrpcClient;
    }

    @QueryMapping
    public DoctorDto doctor(@Argument String id) {
        return doctorLookupGrpcClient.fetch(id);
    }
}
