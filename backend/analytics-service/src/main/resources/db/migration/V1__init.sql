CREATE TABLE event_counters (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type    VARCHAR(100) NOT NULL,
    counter_date  DATE NOT NULL,
    count         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_event_type_date UNIQUE (event_type, counter_date)
);

CREATE TABLE daily_reports (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_date   DATE NOT NULL UNIQUE,
    summary_json  TEXT NOT NULL,
    generated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Quartz's own JobStore tables (QRTZ_*) are created separately via the standard Quartz
-- Postgres DDL script, not managed by this Flyway migration; see docs/adr for the ADR
-- explaining that split (Quartz owns its schema, Flyway owns the domain schema).

CREATE INDEX idx_event_counters_date ON event_counters (counter_date);
