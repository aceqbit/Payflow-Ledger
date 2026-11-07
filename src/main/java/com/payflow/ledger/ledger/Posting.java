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
    public record Acct(UUID id, String currency, long balanceMinor, boolean system, byte[] hashHead) {}

    private final CountingJdbc jdbc;

    public Posting(CountingJdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** SELECT ... FOR UPDATE on both accounts in ascending id order, scoped by merchant. */
    public List<Acct> lockPair(String merchantId, UUID a, UUID b) {
        return jdbc.sql("""
                SELECT id, currency, balance_minor, is_system, hash_head FROM accounts
                WHERE id IN (:a, :b) AND merchant_id = :m ORDER BY id FOR UPDATE""")
                .param("a", a).param("b", b).param("m", merchantId)
                .query((rs, n) -> new Acct(rs.getObject("id", UUID.class), rs.getString("currency"),
                        rs.getLong("balance_minor"), rs.getBoolean("is_system"), rs.getBytes("hash_head")))
                .list();
    }

    public static Acct pick(List<Acct> locked, UUID id, String what) {
        return locked.stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new LedgerException(404, "ACCOUNT_NOT_FOUND", what + " account not found"));
    }

    /** Caller holds the locks (or runs at an isolation that protects the read). Returns the transaction id. */
    public UUID transfer(Acct from, Acct to, long amount) {
        var ctx = com.payflow.ledger.idempotency.IdempotencyContext.get();
        UUID txn = jdbc.sql("INSERT INTO transactions (type, merchant_id, idempotency_key) VALUES ('transfer', :m, :k) RETURNING id")
                .param("m", ctx == null ? null : ctx.merchantId()).param("k", ctx == null ? null : ctx.key())
                .query(UUID.class).single();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime ts = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        byte[] fromHash = HashChain.hash(from.hashHead(), from.id(), txn, -amount, now);
        byte[] toHash = HashChain.hash(to.hashHead(), to.id(), txn, amount, now);

        jdbc.sql("""
                INSERT INTO ledger_entries (txn_id, account_id, amount_minor, prev_hash, hash, created_at)
                VALUES (:t, :fa, :famt, :fp, :fh, :ts), (:t, :ta, :tamt, :tp, :th, :ts)""")
                .param("t", txn)
                .param("fa", from.id()).param("famt", -amount).param("fp", from.hashHead()).param("fh", fromHash)
                .param("ta", to.id()).param("tamt", amount).param("tp", to.hashHead()).param("th", toHash)
                .param("ts", ts)
                .update();

        jdbc.sql("""
                UPDATE accounts a SET balance_minor = a.balance_minor + v.delta, hash_head = v.h, version = a.version + 1
                FROM (VALUES (CAST(:fa AS uuid), CAST(:fd AS bigint), CAST(:fh AS bytea)),
                             (CAST(:ta AS uuid), CAST(:td AS bigint), CAST(:th AS bytea))) AS v(id, delta, h)
                WHERE a.id = v.id""")
                .param("fa", from.id()).param("fd", -amount).param("fh", fromHash)
                .param("ta", to.id()).param("td", amount).param("th", toHash)
                .update();
        return txn;
    }
}
