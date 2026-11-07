package com.payflow.ledger.verify;

import com.payflow.ledger.ledger.HashChain;
import com.payflow.ledger.telemetry.LedgerMetrics;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Walks each account's entries in (created_at, id) order and recomputes the hash chain.
 * Classifies breaks: edit (own hash does not match its content), delete (link points at an entry
 * that no longer exists, or the account head does not match the last entry) and reorder (link
 * points at an entry that exists elsewhere in the account).
 */
@Component
public class ChainVerifier {
    public record Result(long entries, Map<String, Long> breaks) {}

    private final DataSource ds;
    private final LedgerMetrics metrics;

    public ChainVerifier(DataSource ds, LedgerMetrics metrics) {
        this.ds = ds;
        this.metrics = metrics;
    }

    public Result verifyAll() {
        long t0 = System.nanoTime();
        Map<String, Long> breaks = new LinkedHashMap<>();
        for (String k : LedgerMetrics.CHAIN_KINDS) breaks.put(k, 0L);
        long entries = 0;
        try (Connection c = ds.getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            List<UUID> accounts = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("SELECT id FROM accounts ORDER BY id"); ResultSet r = p.executeQuery()) {
                while (r.next()) accounts.add(r.getObject(1, UUID.class));
            }
            for (UUID acc : accounts) {
                entries += verifyAccount(c, acc, breaks);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("chain verification failed", e);
        }
        breaks.forEach(metrics::setChainBreaks);
        metrics.chainEntriesVerified(entries);
        metrics.chainVerify(Duration.ofNanos(System.nanoTime() - t0));
        return new Result(entries, breaks);
    }

    private long verifyAccount(Connection c, UUID acc, Map<String, Long> breaks) throws SQLException {
        record E(UUID txn, long amount, byte[] prev, byte[] hash, long micros) {}
        List<E> rows = new ArrayList<>();
        byte[] head;
        try (PreparedStatement p = c.prepareStatement("SELECT hash_head FROM accounts WHERE id = ?")) {
            p.setObject(1, acc);
            try (ResultSet r = p.executeQuery()) { r.next(); head = r.getBytes(1); }
        }
        try (PreparedStatement p = c.prepareStatement("""
                SELECT txn_id, amount_minor, prev_hash, hash, (extract(epoch FROM created_at) * 1000000)::bigint
                FROM ledger_entries WHERE account_id = ? ORDER BY created_at, id""")) {
            p.setFetchSize(10_000);
            p.setObject(1, acc);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) rows.add(new E(r.getObject(1, UUID.class), r.getLong(2), r.getBytes(3), r.getBytes(4), r.getLong(5)));
            }
        }
        Set<ByteBuffer> hashes = new HashSet<>();
        for (E e : rows) hashes.add(ByteBuffer.wrap(e.hash()));

        byte[] expectedPrev = HashChain.GENESIS;
        boolean lastWasReorder = false;
        for (E e : rows) {
            boolean reorder = false;
            if (!Arrays.equals(e.prev(), expectedPrev)) {
                if (hashes.contains(ByteBuffer.wrap(e.prev()))) {
                    reorder = true;
                    if (!lastWasReorder) breaks.merge("reorder", 1L, Long::sum); // a swapped pair is one incident
                } else {
                    breaks.merge("delete", 1L, Long::sum);
                }
            } else if (!Arrays.equals(HashChain.hash(e.prev(), acc, e.txn(), e.amount(), e.micros()), e.hash())) {
                breaks.merge("edit", 1L, Long::sum);
            }
            lastWasReorder = reorder;
            expectedPrev = e.hash();
        }
        // the account head must be the hash of the last entry (catches a deleted tail)
        if (!Arrays.equals(head, expectedPrev)) {
            breaks.merge("delete", 1L, Long::sum);
        }
        return rows.size();
    }
}
