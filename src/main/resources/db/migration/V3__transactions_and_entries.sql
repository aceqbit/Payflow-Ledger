CREATE TABLE transactions (
    id         uuid PRIMARY KEY DEFAULT uuidv7(),
    type       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    txn_id       uuid        NOT NULL REFERENCES transactions (id),
    account_id   uuid        NOT NULL REFERENCES accounts (id),
    amount_minor bigint      NOT NULL,
    prev_hash    bytea       NOT NULL,
    hash         bytea       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ledger_entries_nonzero CHECK (amount_minor <> 0)
);
CREATE INDEX ledger_entries_account_created_idx ON ledger_entries (account_id, created_at);
CREATE INDEX ledger_entries_txn_idx ON ledger_entries (txn_id);

-- append-only: corrections are new reversing entries
CREATE FUNCTION ledger_entries_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only' USING ERRCODE = '23000';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_reject_mutation();

-- entries of a transaction must sum to 0 at commit
CREATE FUNCTION ledger_entries_check_balanced() RETURNS trigger AS $$
DECLARE s bigint;
BEGIN
    SELECT COALESCE(SUM(amount_minor), 0) INTO s FROM ledger_entries WHERE txn_id = NEW.txn_id;
    IF s <> 0 THEN
        RAISE EXCEPTION 'transaction % entries sum to %, expected 0', NEW.txn_id, s USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_entries_sum_zero
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_check_balanced();
