CREATE TABLE encounters (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id      UUID NOT NULL,
    doctor_id       UUID NOT NULL,
    appointment_id  UUID,
    encounter_date  TIMESTAMPTZ NOT NULL DEFAULT now(),
    notes           TEXT
);

CREATE TABLE diagnoses (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id  UUID NOT NULL REFERENCES encounters(id) ON DELETE CASCADE,
    icd10_code    VARCHAR(20) NOT NULL,
    description   VARCHAR(500) NOT NULL
);

CREATE TABLE medications (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id  UUID NOT NULL REFERENCES encounters(id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    dosage        VARCHAR(150) NOT NULL
);

CREATE TABLE lab_results (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id     UUID NOT NULL REFERENCES encounters(id) ON DELETE CASCADE,
    test_name        VARCHAR(255) NOT NULL,
    result_value     VARCHAR(255) NOT NULL,
    reference_range  VARCHAR(255)
);

CREATE TABLE allergies (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id   UUID NOT NULL,
    substance    VARCHAR(255) NOT NULL,
    severity     VARCHAR(20) NOT NULL
);

CREATE INDEX idx_encounters_patient ON encounters (patient_id, encounter_date DESC);
CREATE INDEX idx_allergies_patient ON allergies (patient_id);
