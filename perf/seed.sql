-- Seed profile: 100 merchants x 100 accounts = 10k accounts, each funded with 1,000,000 minor units
-- from the merchant's system account through real, hash-chained entries (so verifiers pass on a fresh seed).
-- Usage: psql -d ledger_seed -f perf/seed.sql   (database must already be migrated by the app / Flyway)
DO $$
DECLARE
    m int; a int;
    sys_id uuid; acc uuid; txn uuid;
    sys_head bytea; acc_head bytea; sys_hash bytea; acc_hash bytea;
    ts timestamptz;
    fund bigint := 1000000;
    mid text;
BEGIN
    FOR m IN 1..100 LOOP
        mid := 'merchant-' || lpad(m::text, 3, '0');
        INSERT INTO accounts (merchant_id, currency, is_system) VALUES (mid, 'USD', true) RETURNING id, hash_head INTO sys_id, sys_head;
        FOR a IN 1..100 LOOP
            INSERT INTO accounts (merchant_id, currency) VALUES (mid, 'USD') RETURNING id, hash_head INTO acc, acc_head;
            INSERT INTO transactions (type, merchant_id, idempotency_key) VALUES ('seed', mid, 'seed-' || acc) RETURNING id INTO txn;
            ts := date_trunc('milliseconds', clock_timestamp());
            sys_hash := ledger_entry_hash(sys_head, sys_id, txn, -fund, ts);
            acc_hash := ledger_entry_hash(acc_head, acc, txn, fund, ts);
            INSERT INTO ledger_entries (txn_id, account_id, amount_minor, prev_hash, hash, created_at) VALUES
                (txn, sys_id, -fund, sys_head, sys_hash, ts),
                (txn, acc, fund, acc_head, acc_hash, ts);
            UPDATE accounts SET balance_minor = balance_minor - fund, hash_head = sys_hash, version = version + 1 WHERE id = sys_id;
            UPDATE accounts SET balance_minor = fund, hash_head = acc_hash, version = version + 1 WHERE id = acc;
            sys_head := sys_hash;
        END LOOP;
    END LOOP;
END $$;
