-- NOTE: plan section 3 asks for day partitioning, but Postgres requires the partition key in every
-- unique constraint, which would let the same (merchant_id, key) execute twice across midnight.
-- Global uniqueness is a money rule, so the table is not partitioned; retention is by expires_at.
CREATE TABLE idempotency_keys (
    merchant_id   text        NOT NULL,
    key           text        NOT NULL,
    request_hash  bytea       NOT NULL,
    response_code int,
    response_body text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL DEFAULT now() + interval '24 hours',
    PRIMARY KEY (merchant_id, key)
);
CREATE INDEX idempotency_keys_expires_idx ON idempotency_keys (expires_at);
