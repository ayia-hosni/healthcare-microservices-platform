package com.healthplatform.emr.web.dto.fhir;

import java.time.Instant;
import java.util.List;

/** A FHIR DocumentReference resource (http://hl7.org/fhir/documentreference.html), serialized as-is. */
public record DocumentReferenceDto(
        String resourceType,
        String id,
        String status,
        CodeableConceptDto type,
        ReferenceDto subject,
        DocumentContextDto context,
        List<ReferenceDto> author,
        Instant date,
        List<DocumentContentDto> content
) {
    public static DocumentReferenceDto of(String id, String status, CodeableConceptDto type, ReferenceDto subject,
                                           ReferenceDto encounter, ReferenceDto author, Instant date,
                                           AttachmentDto attachment) {
        return new DocumentReferenceDto(
                "DocumentReference", id, status, type, subject,
                new DocumentContextDto(List.of(encounter)),
                List.of(author), date,
                List.of(new DocumentContentDto(attachment))
        );
    }
}
