-- Contract shared with PayFlow Events. Changing columns needs a new migration + progress.md note.
CREATE TABLE outbox (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    type           text        NOT NULL,
    seq            bigint      NOT NULL,
    merchant_id    text        NOT NULL,
    amount_minor   bigint      NOT NULL,
    currency       char(3)     NOT NULL,
    payload        jsonb       NOT NULL,
    traceparent    text,
    occurred_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT outbox_aggregate_seq UNIQUE (aggregate_type, aggregate_id, seq)
);
