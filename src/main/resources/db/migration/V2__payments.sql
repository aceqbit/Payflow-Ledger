CREATE TABLE payments (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    merchant_id text        NOT NULL,
    currency    char(3)     NOT NULL,
    state       text        NOT NULL,
    authorized  bigint      NOT NULL DEFAULT 0,
    captured    bigint      NOT NULL DEFAULT 0,
    refunded    bigint      NOT NULL DEFAULT 0,
    version     bigint      NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT payments_amounts_ok CHECK (authorized >= 0 AND captured >= 0 AND refunded >= 0
                                          AND captured <= authorized AND refunded <= captured)
);
CREATE INDEX payments_merchant_idx ON payments (merchant_id);
