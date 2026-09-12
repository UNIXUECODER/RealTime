CREATE TABLE tenants (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    plan       VARCHAR(50)  NOT NULL DEFAULT 'free',
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenants (id),
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(50)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email)
);

-- public_id is what actually appears in URLs (/webhook/{id}, /ws/{id}, etc.) — the
-- auto-increment `id` never leaves the database. Never expose sequential PKs in a
-- public API: they leak growth-rate information and make enumeration attacks trivial.
CREATE TABLE channels (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES tenants (id),
    public_id           VARCHAR(36)  NOT NULL,
    name                VARCHAR(255) NOT NULL,
    retention_days      INTEGER      NOT NULL DEFAULT 7,
    rate_limit_per_sec  INTEGER,
    dedup_field         VARCHAR(255),
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_channels_public_id UNIQUE (public_id)
);

CREATE INDEX idx_channels_tenant_id ON channels (tenant_id);

CREATE TABLE api_keys (
    id         BIGSERIAL PRIMARY KEY,
    channel_id BIGINT       NOT NULL REFERENCES channels (id),
    key_hash   VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_api_keys_key_hash UNIQUE (key_hash)
);

CREATE INDEX idx_api_keys_channel_id ON api_keys (channel_id);
