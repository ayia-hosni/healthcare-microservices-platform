package com.healthplatform.patient.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class InsuranceInfo {

    @Column(name = "insurance_provider")
    private String provider;

    @Column(name = "insurance_policy_number")
    private String policyNumber;

    @Column(name = "insurance_group_number")
    private String groupNumber;

    protected InsuranceInfo() {}

    public InsuranceInfo(String provider, String policyNumber, String groupNumber) {
        this.provider = provider;
        this.policyNumber = policyNumber;
        this.groupNumber = groupNumber;
    }

    public String getProvider() { return provider; }
    public String getPolicyNumber() { return policyNumber; }
    public String getGroupNumber() { return groupNumber; }
}
