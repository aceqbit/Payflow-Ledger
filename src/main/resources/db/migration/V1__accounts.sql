CREATE TABLE accounts (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    merchant_id   text        NOT NULL,
    currency      char(3)     NOT NULL,
    balance_minor bigint      NOT NULL DEFAULT 0,
    version       bigint      NOT NULL DEFAULT 0,
    hash_head     bytea       NOT NULL DEFAULT decode(repeat('00', 32), 'hex'),
    is_system     boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT accounts_balance_nonneg CHECK (is_system OR balance_minor >= 0),
    CONSTRAINT accounts_hash_head_len CHECK (octet_length(hash_head) = 32)
);
CREATE INDEX accounts_merchant_idx ON accounts (merchant_id);
