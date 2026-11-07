-- Payments move money between a payer and a payee account of the same merchant.
ALTER TABLE payments
    ADD COLUMN payer_account_id uuid   NOT NULL REFERENCES accounts (id),
    ADD COLUMN payee_account_id uuid   NOT NULL REFERENCES accounts (id),
    ADD COLUMN amount_minor     bigint NOT NULL CHECK (amount_minor > 0);
