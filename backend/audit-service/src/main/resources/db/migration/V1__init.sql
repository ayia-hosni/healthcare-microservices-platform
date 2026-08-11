CREATE TABLE audit_records (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(100) NOT NULL,
    aggregate_id    VARCHAR(255) NOT NULL,
    source_topic    VARCHAR(100) NOT NULL,
    correlation_id  VARCHAR(255),
    payload_json    TEXT NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_aggregate ON audit_records (aggregate_id);
CREATE INDEX idx_audit_recorded_at ON audit_records (recorded_at);
