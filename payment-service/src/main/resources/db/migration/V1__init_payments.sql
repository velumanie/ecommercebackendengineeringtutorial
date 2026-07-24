CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID NOT NULL,
    customer_id     UUID NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    amount          NUMERIC(12,2) NOT NULL,
    method          VARCHAR(30) NOT NULL DEFAULT 'CARD',
    -- Nullable — most callers won't send an Idempotency-Key header. The unique index below
    -- is partial (WHERE idempotency_key IS NOT NULL) so any number of payments without one
    -- can coexist.
    idempotency_key VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_payments_order UNIQUE (order_id),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING','AUTHORIZED','CAPTURED','FAILED','REFUNDED'))
);
CREATE UNIQUE INDEX uq_payments_idempotency_key ON payments (idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE transactions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id   UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    type         VARCHAR(20) NOT NULL,
    gateway_ref  VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_transactions_type CHECK (type IN ('AUTH','CAPTURE','REFUND','VOID'))
);
CREATE INDEX idx_transactions_payment ON transactions (payment_id);

-- Transactional outbox: written in the same DB transaction as the payment it describes (see
-- OutboxEvent/PaymentEventProducer), then drained to Kafka by a poller (OutboxPublisher)
-- instead of publishing inline — removes the dual-write gap where a crash between "commit the
-- payment" and "publish to Kafka" would silently drop the event.
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id   UUID NOT NULL,
    event_type     VARCHAR(50) NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    payload        JSONB NOT NULL,
    -- Captured from MDC at write time and re-attached as a Kafka header on publish, so a
    -- trace survives the async gap between the HTTP request and the poller's background
    -- thread — see OutboxPublisher / docs/architecture.html Part 6.
    correlation_id VARCHAR(255),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
