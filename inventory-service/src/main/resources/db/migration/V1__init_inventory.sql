CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE warehouses (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code     VARCHAR(20) NOT NULL,
    location VARCHAR(255) NOT NULL,
    CONSTRAINT uq_warehouses_code UNIQUE (code)
);

CREATE TABLE stock (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id         UUID NOT NULL,
    warehouse_id       UUID NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
    quantity_on_hand   INT NOT NULL DEFAULT 0,
    quantity_reserved  INT NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    version            BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_product_warehouse UNIQUE (product_id, warehouse_id),
    CONSTRAINT ck_stock_nonneg CHECK (quantity_on_hand >= 0 AND quantity_reserved >= 0),
    CONSTRAINT ck_stock_reserved_lte_hand CHECK (quantity_reserved <= quantity_on_hand)
);
CREATE INDEX idx_stock_product ON stock (product_id);

INSERT INTO warehouses (code, location) VALUES ('WH-MAIN', 'Primary distribution center');
