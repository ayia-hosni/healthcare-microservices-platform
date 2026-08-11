package com.healthplatform.emr.web.dto.fhir;

import java.util.List;

public record CodeableConceptDto(List<CodingDto> coding) {}
