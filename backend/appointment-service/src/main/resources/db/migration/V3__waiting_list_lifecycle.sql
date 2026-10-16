-- WaitingListEntry gained a lifecycle (WAITING -> NOTIFIED -> FULFILLED/CANCELLED/EXPIRED) and
-- optimistic locking; V1 only created patient/doctor/created_at. Without these columns
-- ddl-auto=validate refuses to start the service.
ALTER TABLE waiting_list_entries
    ADD COLUMN status      VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    ADD COLUMN notified_at TIMESTAMPTZ,
    ADD COLUMN version     BIGINT      NOT NULL DEFAULT 0;
