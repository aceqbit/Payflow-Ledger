-- One money operation = one transaction per idempotency key, enforced by the database.
-- A second charge with the same key violates this unique constraint instead of double-posting.
ALTER TABLE transactions
    ADD COLUMN merchant_id      text,
    ADD COLUMN idempotency_key  text,
    ADD CONSTRAINT transactions_idem_unique UNIQUE (merchant_id, idempotency_key);
