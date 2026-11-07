-- Turns an account into a hot account with n buckets by moving its balance into the buckets through real,
-- hash-chained entries (so verifiers stay green). make_hot_all(n) converts the first account of merchant-001, which is
-- exactly the single hot account the Gatling skew generator targets (50% of all credits, index 0 of merchant-001).
CREATE OR REPLACE FUNCTION make_hot(p uuid, n int) RETURNS void AS $$
DECLARE
    r accounts%ROWTYPE;
    i int; b uuid; txn uuid; ts timestamptz;
    ph bytea; bh bytea; fh bytea; th bytea;
    pbal bigint; share bigint; amt bigint;
BEGIN
    SELECT * INTO r FROM accounts WHERE id = p FOR UPDATE;
    pbal := r.balance_minor; ph := r.hash_head; share := pbal / n;
    FOR i IN 1..n LOOP
        INSERT INTO accounts (merchant_id, currency, parent_id) VALUES (r.merchant_id, r.currency, p) RETURNING id, hash_head INTO b, bh;
        amt := CASE WHEN i = n THEN pbal - share * (n - 1) ELSE share END;
        IF amt = 0 THEN CONTINUE; END IF;
        INSERT INTO transactions (type) VALUES ('hot-split') RETURNING id INTO txn;
        ts := date_trunc('microseconds', clock_timestamp());
        fh := ledger_entry_hash(ph, p, txn, -amt, ts);
        th := ledger_entry_hash(bh, b, txn, amt, ts);
        INSERT INTO ledger_entries (txn_id, account_id, amount_minor, prev_hash, hash, created_at) VALUES
            (txn, p, -amt, ph, fh, ts), (txn, b, amt, bh, th, ts);
        UPDATE accounts SET balance_minor = amt, hash_head = th, version = version + 1 WHERE id = b;
        ph := fh;
    END LOOP;
    UPDATE accounts SET balance_minor = 0, hash_head = ph, hot_buckets = n, version = version + 1 WHERE id = p;
END $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION make_hot_all(n int) RETURNS int AS $$
DECLARE c int := 0; rec record;
BEGIN
    FOR rec IN SELECT id FROM accounts WHERE merchant_id = 'merchant-001' AND NOT is_system AND parent_id IS NULL ORDER BY id LIMIT 1 LOOP
        PERFORM make_hot(rec.id, n);
        c := c + 1;
    END LOOP;
    RETURN c;
END $$ LANGUAGE plpgsql;
