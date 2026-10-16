package com.healthplatform.emr.domain;

import com.healthplatform.common.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "lab_results")
public class LabResult {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id")
    private Encounter encounter;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String testName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String resultValue;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String referenceRange;

    protected LabResult() {}

    public LabResult(String testName, String resultValue, String referenceRange) {
        this.testName = testName;
        this.resultValue = resultValue;
        this.referenceRange = referenceRange;
    }

    void assignTo(Encounter encounter) { this.encounter = encounter; }

    public UUID getId() { return id; }
    public String getTestName() { return testName; }
    public String getResultValue() { return resultValue; }
    public String getReferenceRange() { return referenceRange; }
}
