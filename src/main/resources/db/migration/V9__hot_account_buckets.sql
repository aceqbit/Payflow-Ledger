-- Hot-account buckets: a hot account keeps no balance of its own; its money lives in N bucket accounts
-- (ordinary accounts with parent_id set), each with its own hash chain. Credits go to a random bucket,
-- debits lock buckets in id order until funds are enough, balance reads sum the buckets.
ALTER TABLE accounts
    ADD COLUMN parent_id   uuid REFERENCES accounts (id),
    ADD COLUMN hot_buckets int  NOT NULL DEFAULT 0 CHECK (hot_buckets >= 0);
CREATE INDEX accounts_parent_idx ON accounts (parent_id) WHERE parent_id IS NOT NULL;
