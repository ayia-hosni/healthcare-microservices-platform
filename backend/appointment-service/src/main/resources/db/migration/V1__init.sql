CREATE TABLE appointments (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id              UUID NOT NULL,
    doctor_id               UUID NOT NULL,
    scheduled_start         TIMESTAMPTZ NOT NULL,
    scheduled_end           TIMESTAMPTZ NOT NULL,
    status                  VARCHAR(20) NOT NULL,
    idempotency_key         VARCHAR(255) NOT NULL UNIQUE,
    cancellation_reason     TEXT,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_doctor_slot UNIQUE (doctor_id, scheduled_start)
);

CREATE TABLE waiting_list_entries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id  UUID NOT NULL,
    doctor_id   UUID NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_appointments_patient ON appointments (patient_id);
CREATE INDEX idx_appointments_doctor_status_start ON appointments (doctor_id, status, scheduled_start);
