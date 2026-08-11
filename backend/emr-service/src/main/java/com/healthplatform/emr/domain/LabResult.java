package com.healthplatform.emr.domain;

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

    @Column(nullable = false)
    private String testName;

    @Column(nullable = false)
    private String resultValue;

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
