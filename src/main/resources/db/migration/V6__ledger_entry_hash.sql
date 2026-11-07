-- Must stay byte-identical to com.payflow.ledger.ledger.HashChain:
-- SHA-256(prev(32B) || account(16B) || txn(16B) || amount(int64 BE) || created_at(epoch micros int64 BE))
CREATE FUNCTION ledger_entry_hash(prev bytea, account uuid, txn uuid, amount bigint, created timestamptz)
RETURNS bytea AS $$
    SELECT sha256(prev || uuid_send(account) || uuid_send(txn) || int8send(amount)
                  || int8send((extract(epoch FROM created) * 1000000)::bigint));
$$ LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE;
