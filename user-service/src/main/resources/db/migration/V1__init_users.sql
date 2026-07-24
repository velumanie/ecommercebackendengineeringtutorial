CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                  VARCHAR(255) NOT NULL,
    password_hash          VARCHAR(255) NOT NULL,
    first_name             VARCHAR(100) NOT NULL,
    last_name              VARCHAR(100) NOT NULL,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    -- Brute-force protection (see AuthServiceImpl.login / docs/architecture.html Part 7):
    -- five consecutive failed attempts locks the account for 15 minutes.
    failed_login_attempts  INT          NOT NULL DEFAULT 0,
    locked_until           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE','LOCKED','DISABLED'))
);
CREATE INDEX idx_users_status ON users (status);

CREATE TABLE roles (
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name  VARCHAR(50) NOT NULL,
    CONSTRAINT uq_roles_name UNIQUE (name)
);

CREATE TABLE permissions (
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code  VARCHAR(100) NOT NULL,
    CONSTRAINT uq_permissions_code UNIQUE (code)
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE RESTRICT,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

INSERT INTO roles (name) VALUES ('ADMIN'), ('MANAGER'), ('CUSTOMER');

-- Dev/local-only bootstrap seed: without this, a fresh deployment has no way to create
-- its first user at all (POST /api/v1/users requires ROLE_ADMIN, and there is no public
-- self-registration endpoint), so nobody could ever log in. This is what unblocks the
-- guided smoke test in docs/local-deployment.html.
--
-- Credentials: admin@example.com / Admin@12345
-- DO NOT rely on this in any shared/real environment — rotate or remove this row before
-- deploying anywhere the JWT_SECRET/DB credentials aren't also dev-only throwaway values.
INSERT INTO users (id, email, password_hash, first_name, last_name, status)
VALUES (
    gen_random_uuid(),
    'admin@example.com',
    '$2b$12$r08jto1YYR8aeL2sLWMK0.pCv.CnsfNCW9k9Oavtj73Ct1HLY.cOu',
    'Dev',
    'Admin',
    'ACTIVE'
);

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u, roles r
WHERE u.email = 'admin@example.com' AND r.name = 'ADMIN';
