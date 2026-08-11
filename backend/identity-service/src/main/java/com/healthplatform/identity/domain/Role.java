package com.healthplatform.identity.domain;

/** Coarse-grained roles used for RBAC. Method-level @PreAuthorize checks reference these. */
public enum Role {
    ROLE_PATIENT,
    ROLE_DOCTOR,
    ROLE_NURSE,
    ROLE_BILLING_CLERK,
    ROLE_ADMIN
}
