package com.payflow.ledger.ledger;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Writes a balanced pair of entries, advances both hash chains and updates both balances. */
@Component
public class Posting {
    public record Acct(UUID id, String currency, long balanceMinor, boolean system, byte[] hashHead, long version, int hotBuckets) {}

    /** One side of a posting: a signed amount on one account. Legs of a transaction sum to 0. */
    public record Leg(Acct acct, long delta) {}

    private final CountingJdbc jdbc;

    public Posting(CountingJdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** SELECT ... FOR UPDATE on both accounts in ascending id order, scoped by merchant. */
    public List<Acct> lockPair(String merchantId, UUID a, UUID b) {
        return jdbc.sql("""
                SELECT id, currency, balance_minor, is_system, hash_head, version, hot_buckets FROM accounts
                WHERE id IN (:a, :b) AND merchant_id = :m AND hot_buckets = 0 ORDER BY id FOR UPDATE""")
                .param("a", a).param("b", b).param("m", merchantId)
                .query(ACCT).list();
    }

    /** All buckets of a hot account, locked in id order. */
    public List<Acct> lockBuckets(String merchantId, UUID parent) {
        return jdbc.sql("""
                SELECT id, currency, balance_minor, is_system, hash_head, version, hot_buckets FROM accounts
                WHERE parent_id = :p AND merchant_id = :m ORDER BY id FOR UPDATE""")
                .param("p", parent).param("m", merchantId).query(ACCT).list();
    }

    /** One random bucket of a hot account (credits spread over buckets), locked. */
    public Acct lockRandomBucket(String merchantId, UUID parent, int buckets) {
        return jdbc.sql("""
                SELECT id, currency, balance_minor, is_system, hash_head, version, hot_buckets FROM accounts
                WHERE parent_id = :p AND merchant_id = :m ORDER BY id OFFSET :off LIMIT 1 FOR UPDATE""")
                .param("p", parent).param("m", merchantId)
                .param("off", java.util.concurrent.ThreadLocalRandom.current().nextInt(Math.max(1, buckets)))
                .query(ACCT).optional()
                .orElseThrow(() -> new LedgerException(500, "HOT_BUCKETS_MISSING", "hot account has no buckets"));
    }

    /** Same read without locks, for the OPTIMISTIC and SERIALIZABLE strategies. */
    public List<Acct> readPair(String merchantId, UUID a, UUID b) {
        return jdbc.sql("""
                SELECT id, currency, balance_minor, is_system, hash_head, version, hot_buckets FROM accounts
                WHERE id IN (:a, :b) AND merchant_id = :m ORDER BY id""")
                .param("a", a).param("b", b).param("m", merchantId)
                .query(ACCT).list();
    }

    private static final org.springframework.jdbc.core.RowMapper<Acct> ACCT = (rs, n) -> new Acct(
            rs.getObject("id", UUID.class), rs.getString("currency"), rs.getLong("balance_minor"),
            rs.getBoolean("is_system"), rs.getBytes("hash_head"), rs.getLong("version"), rs.getInt("hot_buckets"));

    public static Acct pick(List<Acct> locked, UUID id, String what) {
        return locked.stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new LedgerException(404, "ACCOUNT_NOT_FOUND", what + " account not found"));
    }

    /** Caller holds the locks (or runs at an isolation that protects the read). Returns the transaction id. */
    public UUID transfer(Acct from, Acct to, long amount) {
        return transfer(from, to, amount, false);
    }

    /** optimistic=true: update balances first with a version check (fail fast), then write the entries. */
    public UUID transfer(Acct from, Acct to, long amount, boolean optimistic) {
        var ctx = com.payflow.ledger.idempotency.IdempotencyContext.get();
        UUID txn = jdbc.sql("INSERT INTO transactions (type, merchant_id, idempotency_key) VALUES ('transfer', :m, :k) RETURNING id")
                .param("m", ctx == null ? null : ctx.merchantId()).param("k", ctx == null ? null : ctx.key())
                .query(UUID.class).single();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime ts = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        byte[] fromHash = HashChain.hash(from.hashHead(), from.id(), txn, -amount, now);
        byte[] toHash = HashChain.hash(to.hashHead(), to.id(), txn, amount, now);

        int updated = jdbc.sql("""
                UPDATE accounts a SET balance_minor = a.balance_minor + v.delta, hash_head = v.h, version = a.version + 1
                FROM (VALUES (CAST(:fa AS uuid), CAST(:fd AS bigint), CAST(:fh AS bytea), CAST(:fv AS bigint)),
                             (CAST(:ta AS uuid), CAST(:td AS bigint), CAST(:th AS bytea), CAST(:tv AS bigint))) AS v(id, delta, h, ver)
                WHERE a.id = v.id AND (NOT :opt OR a.version = v.ver)""")
                .param("fa", from.id()).param("fd", -amount).param("fh", fromHash).param("fv", from.version())
                .param("ta", to.id()).param("td", amount).param("th", toHash).param("tv", to.version())
                .param("opt", optimistic)
                .update();
        if (updated != 2) throw new RetryableConflict("version_conflict");
        jdbc.sql("""
                INSERT INTO ledger_entries (txn_id, account_id, amount_minor, prev_hash, hash, created_at)
                VALUES (:t, :fa, :famt, :fp, :fh, :ts), (:t, :ta, :tamt, :tp, :th, :ts)""")
                .param("t", txn)
                .param("fa", from.id()).param("famt", -amount).param("fp", from.hashHead()).param("fh", fromHash)
                .param("ta", to.id()).param("tamt", amount).param("tp", to.hashHead()).param("th", toHash)
                .param("ts", ts)
                .update();

        return txn;
    }

    /** N-leg posting (used by hot accounts): one transaction, one multi-row entry insert, one balance update. */
    public UUID postLegs(List<Leg> legs) {
        long sum = legs.stream().mapToLong(Leg::delta).sum();
        if (sum != 0) throw new IllegalStateException("legs must sum to 0, got " + sum);
        var ctx = com.payflow.ledger.idempotency.IdempotencyContext.get();
        UUID txn = jdbc.sql("INSERT INTO transactions (type, merchant_id, idempotency_key) VALUES ('transfer', :m, :k) RETURNING id")
                .param("m", ctx == null ? null : ctx.merchantId()).param("k", ctx == null ? null : ctx.key())
                .query(UUID.class).single();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime ts = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        StringBuilder entries = new StringBuilder("INSERT INTO ledger_entries (txn_id, account_id, amount_minor, prev_hash, hash, created_at) VALUES ");
        StringBuilder values = new StringBuilder();
        var entryStmt = new java.util.ArrayList<Object[]>();
        byte[][] hashes = new byte[legs.size()][];
        for (int i = 0; i < legs.size(); i++) {
            Leg l = legs.get(i);
            hashes[i] = HashChain.hash(l.acct().hashHead(), l.acct().id(), txn, l.delta(), now);
            if (i > 0) { entries.append(", "); values.append(", "); }
            entries.append("(:t, :a").append(i).append(", :d").append(i).append(", :p").append(i).append(", :h").append(i).append(", :ts)");
            values.append("(CAST(:a").append(i).append(" AS uuid), CAST(:d").append(i).append(" AS bigint), CAST(:h").append(i).append(" AS bytea))");
        }
        var e = jdbc.sql(entries.toString()).param("t", txn).param("ts", ts);
        var u = jdbc.sql("UPDATE accounts a SET balance_minor = a.balance_minor + v.delta, hash_head = v.h, version = a.version + 1 FROM (VALUES "
                + values + ") AS v(id, delta, h) WHERE a.id = v.id");
        for (int i = 0; i < legs.size(); i++) {
            Leg l = legs.get(i);
            e = e.param("a" + i, l.acct().id()).param("d" + i, l.delta()).param("p" + i, l.acct().hashHead()).param("h" + i, hashes[i]);
            u = u.param("a" + i, l.acct().id()).param("d" + i, l.delta()).param("h" + i, hashes[i]);
        }
        u.update();
        e.update();
        return txn;
    }
}
