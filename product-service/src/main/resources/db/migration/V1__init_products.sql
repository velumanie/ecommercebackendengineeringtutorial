CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE categories (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(120) NOT NULL,
    parent_id  UUID REFERENCES categories(id) ON DELETE SET NULL,
    CONSTRAINT uq_categories_name_parent UNIQUE (name, parent_id)
);

CREATE TABLE products (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku           VARCHAR(64) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    price         NUMERIC(12,2) NOT NULL,
    category_id   UUID REFERENCES categories(id) ON DELETE SET NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT ck_products_price CHECK (price >= 0),
    CONSTRAINT ck_products_status CHECK (status IN ('ACTIVE','DISCONTINUED'))
);
CREATE INDEX idx_products_category ON products (category_id);
CREATE INDEX idx_products_name_trgm ON products USING gin (name gin_trgm_ops);
