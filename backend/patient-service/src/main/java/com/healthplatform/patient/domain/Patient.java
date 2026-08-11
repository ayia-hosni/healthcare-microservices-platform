package com.healthplatform.patient.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Patient aggregate root. The id here is the SAME UUID as the identity-service User id
 * (shared identity across bounded contexts) — this service never issues its own auth
 * identifiers, it just enriches the identity with clinical/demographic data.
 */
@Entity
@Table(name = "patients")
public class Patient {

    @Id
    private UUID id; // == identity-service user id, assigned explicitly (no @GeneratedValue)

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false)
    private LocalDate dateOfBirth;

    private String phoneNumber;

    @Column(nullable = false)
    private String email;

    @Embedded
    private InsuranceInfo insurance;

    @Column(columnDefinition = "TEXT")
    private String medicalHistorySummary;

    @Version
    private Long version; // optimistic locking: concurrent profile edits shouldn't silently clobber each other

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Patient() {}

    public Patient(UUID id, String firstName, String lastName, LocalDate dateOfBirth, String email) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.dateOfBirth = dateOfBirth;
        this.email = email;
    }

    public UUID getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getEmail() { return email; }
    public InsuranceInfo getInsurance() { return insurance; }
    public void setInsurance(InsuranceInfo insurance) { this.insurance = insurance; }
    public String getMedicalHistorySummary() { return medicalHistorySummary; }
    public void setMedicalHistorySummary(String summary) { this.medicalHistorySummary = summary; }
    public Long getVersion() { return version; }
}
