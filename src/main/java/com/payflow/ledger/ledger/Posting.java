package com.payflow.ledger.ledger;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Writes a balanced pair of entries, advances both hash chains and updates both balances. */
@Component
public class Posting {
    public record Acct(UUID id, String currency, long balanceMinor, boolean system, byte[] hashHead) {}

    private final CountingJdbc jdbc;

    public Posting(CountingJdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** Caller holds the locks (or runs at an isolation that protects the read). Returns the transaction id. */
    public UUID transfer(Acct from, Acct to, long amount) {
        UUID txn = jdbc.sql("INSERT INTO transactions (type) VALUES ('transfer') RETURNING id").query(UUID.class).single();
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
