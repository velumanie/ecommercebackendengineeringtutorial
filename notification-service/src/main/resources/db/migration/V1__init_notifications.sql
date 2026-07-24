CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE notification_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type  VARCHAR(50) NOT NULL,
    source_id   UUID NOT NULL,
    payload     JSONB NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_events_status CHECK (status IN ('RECEIVED','PROCESSED','FAILED')),
    -- Kafka's at-least-once delivery means this consumer can see the same event twice after a
    -- rebalance; this constraint is the dedup key (see PaymentEventConsumer /
    -- docs/architecture.html Part 6) — payment-service's payments.order_id is already UNIQUE,
    -- so (event_type, source_id) is too.
    CONSTRAINT uq_notification_events_type_source UNIQUE (event_type, source_id)
);
CREATE INDEX idx_notification_events_source ON notification_events (source_id);

CREATE TABLE email_logs (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id     UUID REFERENCES notification_events(id) ON DELETE SET NULL,
    recipient    VARCHAR(255) NOT NULL,
    subject      VARCHAR(255) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    sent_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_email_logs_status CHECK (status IN ('QUEUED','SENT','FAILED'))
);
CREATE INDEX idx_email_logs_recipient ON email_logs (recipient, created_at DESC);
