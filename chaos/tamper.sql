-- Tamper test helpers. Run as a superuser in the tamper database (a copy of ledger_seed).

-- 1. Grow the ledger: n random transfers between non-system accounts of one merchant, written as real
--    hash-chained entries (so the verifier starts from a clean chain). Each transfer = 2 entries.
CREATE OR REPLACE FUNCTION chaos_seed_entries(n int) RETURNS void AS $$
DECLARE
    ids uuid[]; heads bytea[]; bals bigint[]; merch int[];
    i int; k int; a int; b int; amt bigint; txn uuid; ts timestamptz; fh bytea; th bytea; total int;
    first_idx int[]; per int;
BEGIN
    SELECT array_agg(id ORDER BY merchant_id, id), array_agg(hash_head ORDER BY merchant_id, id), array_agg(balance_minor ORDER BY merchant_id, id)
      INTO ids, heads, bals FROM accounts WHERE NOT is_system;
    total := array_length(ids, 1);
    per := 100; -- seed layout: 100 accounts per merchant, contiguous
    FOR i IN 1..n LOOP
        k := floor(random() * (total / per))::int;
        a := k * per + 1 + floor(random() * per)::int;
        b := k * per + 1 + floor(random() * per)::int;
        IF a = b THEN b := k * per + 1 + (a - k * per) % per; END IF;
        amt := 1 + floor(random() * 100)::bigint;
        IF bals[a] < amt THEN CONTINUE; END IF;
        INSERT INTO transactions (type) VALUES ('chaos') RETURNING id INTO txn;
        ts := date_trunc('microseconds', clock_timestamp());
        fh := ledger_entry_hash(heads[a], ids[a], txn, -amt, ts);
        th := ledger_entry_hash(heads[b], ids[b], txn, amt, ts);
        INSERT INTO ledger_entries (txn_id, account_id, amount_minor, prev_hash, hash, created_at) VALUES
            (txn, ids[a], -amt, heads[a], fh, ts), (txn, ids[b], amt, heads[b], th, ts);
        heads[a] := fh; heads[b] := th; bals[a] := bals[a] - amt; bals[b] := bals[b] + amt;
    END LOOP;
    -- write balances and heads back once
    UPDATE accounts x SET hash_head = v.h, balance_minor = v.bal, version = x.version + 1
      FROM (SELECT unnest(ids) AS id, unnest(heads) AS h, unnest(bals) AS bal) v WHERE x.id = v.id;
END $$ LANGUAGE plpgsql;

-- 2. Mutations. One mutation per account (distinct accounts, entry well inside the chain) so each is an independent incident.
CREATE TABLE IF NOT EXISTS tamper_log (id serial PRIMARY KEY, kind text NOT NULL, account_id uuid NOT NULL, entry_id uuid);

CREATE OR REPLACE FUNCTION chaos_tamper(edits int, deletes int, reorders int) RETURNS void AS $$
DECLARE
    acc uuid; e1 uuid; e2 uuid; c1 timestamptz; c2 timestamptz; kind text; n int := 0;
BEGIN
    ALTER TABLE ledger_entries DISABLE TRIGGER USER;
    FOR acc IN SELECT account_id FROM ledger_entries GROUP BY account_id HAVING count(*) >= 30 ORDER BY random() LIMIT (edits + deletes + reorders) LOOP
        n := n + 1;
        kind := CASE WHEN n <= edits THEN 'edit' WHEN n <= edits + deletes THEN 'delete' ELSE 'reorder' END;
        IF kind = 'reorder' THEN
            -- pick neighbours with DIFFERENT timestamps: swapping equal timestamps would change nothing
            SELECT x.id, x.created_at, x.nid, x.nts INTO e1, c1, e2, c2 FROM (
                SELECT id, created_at, lead(id) OVER w AS nid, lead(created_at) OVER w AS nts
                FROM ledger_entries WHERE account_id = acc WINDOW w AS (ORDER BY created_at, id)
                ORDER BY created_at, id OFFSET 10) x
            WHERE x.nts <> x.created_at ORDER BY x.created_at, x.id LIMIT 1;
            UPDATE ledger_entries SET created_at = c2 WHERE id = e1;
            UPDATE ledger_entries SET created_at = c1 WHERE id = e2;
        ELSE
            SELECT id INTO e1 FROM ledger_entries WHERE account_id = acc ORDER BY created_at, id OFFSET 10 LIMIT 1;
            IF kind = 'edit' THEN
                UPDATE ledger_entries SET amount_minor = amount_minor + CASE WHEN amount_minor = -1 THEN 2 ELSE 1 END WHERE id = e1;
            ELSE
                DELETE FROM ledger_entries WHERE id = e1;
            END IF;
        END IF;
        INSERT INTO tamper_log (kind, account_id, entry_id) VALUES (kind, acc, e1);
    END LOOP;
    ALTER TABLE ledger_entries ENABLE TRIGGER USER;
END $$ LANGUAGE plpgsql;
