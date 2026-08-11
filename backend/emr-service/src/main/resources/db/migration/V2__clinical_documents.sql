CREATE TABLE clinical_documents (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id      UUID NOT NULL REFERENCES encounters(id) ON DELETE CASCADE,
    patient_id        UUID NOT NULL,
    author_id         UUID NOT NULL,
    status            VARCHAR(20) NOT NULL,
    type_system       VARCHAR(255) NOT NULL,
    type_code         VARCHAR(50) NOT NULL,
    type_display      VARCHAR(255) NOT NULL,
    title             VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    object_key        VARCHAR(500) NOT NULL,
    size_bytes        BIGINT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_clinical_documents_patient ON clinical_documents (patient_id, created_at DESC);
CREATE INDEX idx_clinical_documents_encounter ON clinical_documents (encounter_id);
