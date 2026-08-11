package com.healthplatform.emr.web.dto.fhir;

import java.util.List;

/** A FHIR searchset Bundle wrapping zero or more DocumentReference resources. */
public record BundleDto(String resourceType, String type, int total, List<BundleEntryDto> entry) {
    public static BundleDto searchset(List<DocumentReferenceDto> resources) {
        return new BundleDto("Bundle", "searchset", resources.size(),
                resources.stream().map(BundleEntryDto::new).toList());
    }
}
