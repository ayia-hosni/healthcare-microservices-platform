package com.healthplatform.patient.domain;

import com.healthplatform.common.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;

@Embeddable
public class InsuranceInfo {

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "insurance_provider", columnDefinition = "TEXT")
    private String provider;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "insurance_policy_number", columnDefinition = "TEXT")
    private String policyNumber;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "insurance_group_number", columnDefinition = "TEXT")
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
