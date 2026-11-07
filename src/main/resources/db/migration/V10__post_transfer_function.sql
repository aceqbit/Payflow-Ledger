-- T4: the whole PESSIMISTIC transfer (idempotency guard, ordered row locks, entries, hash chain, balances, outbox,
-- stored response) as one function call = one round trip. Used only when ledger.transfer.sql-function=true.
-- Returns (outcome, status, body). outcome: new | replay | mismatch.
-- Raises SQLSTATE 'PF001' when a hot account is involved (the Java path handles buckets) and 'PF404' / 'PF400'
-- for missing accounts / currency mismatch, which roll the whole transaction back.
CREATE FUNCTION post_transfer(
    p_merchant text, p_key text, p_request_hash bytea,
    p_from uuid, p_to uuid, p_amount bigint, p_currency text,
    p_traceparent text, p_outbox_delete boolean)
RETURNS TABLE (outcome text, status int, body text) AS $$
DECLARE
    v_inserted int;
    v_hash bytea; v_code int; v_body text;
    a_from accounts%ROWTYPE; a_to accounts%ROWTYPE; n_locked int := 0;
    r accounts%ROWTYPE;
    v_txn uuid; v_ts timestamptz; v_fh bytea; v_th bytea; v_oid uuid;
BEGIN
    INSERT INTO idempotency_keys (merchant_id, key, request_hash) VALUES (p_merchant, p_key, p_request_hash)
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS v_inserted = ROW_COUNT;
    IF v_inserted = 0 THEN
        SELECT request_hash, response_code, response_body INTO v_hash, v_code, v_body
          FROM idempotency_keys WHERE merchant_id = p_merchant AND key = p_key;
        IF v_hash <> p_request_hash THEN
            RETURN QUERY SELECT 'mismatch'::text, 422, NULL::text;
        ELSE
            RETURN QUERY SELECT 'replay'::text, v_code, v_body;
        END IF;
        RETURN;
    END IF;

    PERFORM set_config('lock_timeout', '2s', true);

    FOR r IN SELECT * FROM accounts WHERE id IN (p_from, p_to) AND merchant_id = p_merchant AND hot_buckets = 0 ORDER BY id FOR UPDATE LOOP
        n_locked := n_locked + 1;
        IF r.id = p_from THEN a_from := r; ELSE a_to := r; END IF;
    END LOOP;
    IF n_locked < 2 THEN
        IF (SELECT count(*) FROM accounts WHERE id IN (p_from, p_to) AND merchant_id = p_merchant) < 2 THEN
            RAISE EXCEPTION 'account not found' USING ERRCODE = 'PF404';
        END IF;
        RAISE EXCEPTION 'hot account: use the Java path' USING ERRCODE = 'PF001';
    END IF;
    IF trim(a_from.currency) <> p_currency OR trim(a_to.currency) <> p_currency THEN
        RAISE EXCEPTION 'accounts and transfer must share one currency' USING ERRCODE = 'PF400';
    END IF;

    IF NOT a_from.is_system AND a_from.balance_minor < p_amount THEN
        v_body := jsonb_build_object('type', 'about:blank', 'title', 'INSUFFICIENT_FUNDS', 'status', 409,
                                     'detail', 'insufficient funds', 'code', 'INSUFFICIENT_FUNDS')::text;
        UPDATE idempotency_keys SET response_code = 409, response_body = v_body WHERE merchant_id = p_merchant AND key = p_key;
        RETURN QUERY SELECT 'new'::text, 409, v_body;
        RETURN;
    END IF;

    INSERT INTO transactions (type, merchant_id, idempotency_key) VALUES ('transfer', p_merchant, p_key) RETURNING id INTO v_txn;
    v_ts := date_trunc('microseconds', clock_timestamp());
    v_fh := ledger_entry_hash(a_from.hash_head, a_from.id, v_txn, -p_amount, v_ts);
    v_th := ledger_entry_hash(a_to.hash_head, a_to.id, v_txn, p_amount, v_ts);
    INSERT INTO ledger_entries (txn_id, account_id, amount_minor, prev_hash, hash, created_at) VALUES
        (v_txn, a_from.id, -p_amount, a_from.hash_head, v_fh, v_ts),
        (v_txn, a_to.id, p_amount, a_to.hash_head, v_th, v_ts);
    UPDATE accounts SET balance_minor = balance_minor - p_amount, hash_head = v_fh, version = version + 1 WHERE id = a_from.id;
    UPDATE accounts SET balance_minor = balance_minor + p_amount, hash_head = v_th, version = version + 1 WHERE id = a_to.id;

    v_body := jsonb_build_object('transferId', v_txn, 'status', 'POSTED', 'fromAccountId', p_from, 'toAccountId', p_to,
                                 'amountMinor', p_amount, 'currency', p_currency)::text;
    INSERT INTO outbox (aggregate_type, aggregate_id, type, seq, merchant_id, amount_minor, currency, payload, traceparent)
    VALUES ('transfer', v_txn::text, 'TransferPosted', 1, p_merchant, p_amount, p_currency, v_body::jsonb, p_traceparent)
    RETURNING id INTO v_oid;
    IF p_outbox_delete THEN
        DELETE FROM outbox WHERE id = v_oid;
    END IF;
    UPDATE idempotency_keys SET response_code = 201, response_body = v_body WHERE merchant_id = p_merchant AND key = p_key;
    RETURN QUERY SELECT 'new'::text, 201, v_body;
END $$ LANGUAGE plpgsql;
