package com.healthplatform.patient.domain;

import com.healthplatform.common.crypto.EncryptedLocalDateConverter;
import com.healthplatform.common.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Patient aggregate root. The id here is the SAME UUID as the identity-service User id
 * (shared identity across bounded contexts) — this service never issues its own auth
 * identifiers, it just enriches the identity with clinical/demographic data.
 *
 * The names, dateOfBirth, phoneNumber, email and medicalHistorySummary are PHI and encrypted
 * at rest (EncryptedStringConverter / EncryptedLocalDateConverter, AES-256-GCM). Those columns
 * hold ciphertext, which is why name search moved out of SQL into PatientService.search().
 */
@Entity
@Table(name = "patients")
public class Patient {

    @Id
    private UUID id; // == identity-service user id, assigned explicitly (no @GeneratedValue)

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String firstName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String lastName;

    @Convert(converter = EncryptedLocalDateConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private LocalDate dateOfBirth;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String phoneNumber;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String email;

    @Embedded
    private InsuranceInfo insurance;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String medicalHistorySummary;

    // A flag and a timestamp, not PHI, so not encrypted like the fields above.
    @Column(nullable = false)
    private boolean consentGranted = false;

    private Instant consentUpdatedAt;

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
    public boolean isConsentGranted() { return consentGranted; }
    public Instant getConsentUpdatedAt() { return consentUpdatedAt; }

    public void grantConsent(Instant when) {
        this.consentGranted = true;
        this.consentUpdatedAt = when;
    }

    public void revokeConsent(Instant when) {
        this.consentGranted = false;
        this.consentUpdatedAt = when;
    }

    public Long getVersion() { return version; }
}
