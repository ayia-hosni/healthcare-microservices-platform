CREATE TABLE notification_logs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id   VARCHAR(255) NOT NULL,
    channel        VARCHAR(20) NOT NULL,
    template_code  VARCHAR(100) NOT NULL,
    status         VARCHAR(20) NOT NULL,
    sent_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_logs_recipient ON notification_logs (recipient_id);
