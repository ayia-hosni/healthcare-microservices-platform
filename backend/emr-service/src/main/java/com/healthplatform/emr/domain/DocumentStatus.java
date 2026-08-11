package com.healthplatform.emr.domain;

/** Mirrors the FHIR DocumentReference.status value set (http://hl7.org/fhir/document-reference-status). */
public enum DocumentStatus {
    CURRENT("current"),
    SUPERSEDED("superseded"),
    ENTERED_IN_ERROR("entered-in-error");

    private final String fhirCode;

    DocumentStatus(String fhirCode) {
        this.fhirCode = fhirCode;
    }

    public String fhirCode() {
        return fhirCode;
    }
}
