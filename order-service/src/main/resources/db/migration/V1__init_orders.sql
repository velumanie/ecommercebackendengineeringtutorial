CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE orders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_amount    NUMERIC(12,2) NOT NULL,
    -- Nullable — most callers won't send an Idempotency-Key header. The unique index below
    -- is partial (WHERE idempotency_key IS NOT NULL) so any number of orders without one can
    -- coexist.
    idempotency_key VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_orders_status CHECK (status IN
        ('PENDING','CONFIRMED','PAID','SHIPPED','DELIVERED','CANCELLED','FAILED')),
    CONSTRAINT ck_orders_total_amount CHECK (total_amount >= 0)
);

-- High-volume, time-ordered, read-mostly-recent: at scale this table is range-partitioned
-- by created_at (quarterly) — see docs/architecture.html Part 2. Kept as a single table
-- here so the reference implementation stays a straightforward JPA @Entity mapping.
CREATE INDEX idx_orders_customer ON orders (customer_id, created_at DESC);
CREATE INDEX idx_orders_status ON orders (status) WHERE status NOT IN ('DELIVERED', 'CANCELLED');
CREATE UNIQUE INDEX uq_orders_idempotency_key ON orders (idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE order_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id  UUID NOT NULL,
    quantity    INT NOT NULL,
    unit_price  NUMERIC(12,2) NOT NULL,
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price >= 0)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);
CREATE INDEX idx_order_items_product ON order_items (product_id);

-- Transactional outbox: written in the same DB transaction as the order it describes (see
-- OutboxEvent/OrderEventProducer), then drained to Kafka by a poller (OutboxPublisher) instead
-- of publishing inline — removes the dual-write gap where a crash between "commit the order"
-- and "publish to Kafka" would silently drop the event.
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

-- The poller's own query pattern — find unpublished rows, oldest first.
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
