CREATE TABLE patients (
    id                          UUID PRIMARY KEY,
    first_name                  VARCHAR(100) NOT NULL,
    last_name                   VARCHAR(100) NOT NULL,
    date_of_birth               DATE NOT NULL,
    phone_number                VARCHAR(30),
    email                       VARCHAR(255) NOT NULL,
    insurance_provider          VARCHAR(150),
    insurance_policy_number     VARCHAR(100),
    insurance_group_number      VARCHAR(100),
    medical_history_summary     TEXT,
    version                     BIGINT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_patients_last_name ON patients (lower(last_name));
CREATE INDEX idx_patients_first_name ON patients (lower(first_name));
