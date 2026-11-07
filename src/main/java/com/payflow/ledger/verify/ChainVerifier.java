package com.payflow.ledger.verify;

import com.payflow.ledger.ledger.HashChain;
import com.payflow.ledger.telemetry.LedgerMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Walks each account's entries in (created_at, id) order and recomputes the hash chain, streaming through a
 * server-side cursor (fetch size 10k, O(1) memory per account). Accounts are verified in parallel on virtual threads,
 * each chunk of accounts on its own connection.
 *
 * Break kinds: edit (own hash does not match its content), delete (link points at an entry that no longer exists, or the
 * account head does not match the last entry) and reorder (link points at an entry that exists elsewhere in the account).
 */
@Component
public class ChainVerifier {
    public record Result(long entries, Map<String, Long> breaks, double seconds) {
        public double entriesPerSecond() {
            return seconds <= 0 ? 0 : entries / seconds;
        }
    }

    private static final int FETCH_SIZE = 10_000;

    private final DataSource ds;
    private final LedgerMetrics metrics;
    private final int parallelism;

    public ChainVerifier(DataSource ds, LedgerMetrics metrics, @Value("${ledger.verify.parallelism:#{T(java.lang.Runtime).getRuntime().availableProcessors()}}") int parallelism) {
        this.ds = ds;
        this.metrics = metrics;
        this.parallelism = Math.max(1, parallelism);
    }

    public Result verifyAll() {
        long t0 = System.nanoTime();
        List<UUID> ids = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement("SELECT id FROM accounts ORDER BY id");
             ResultSet r = p.executeQuery()) {
            while (r.next()) ids.add(r.getObject(1, UUID.class));
        } catch (SQLException e) {
            throw new IllegalStateException("chain verification failed", e);
        }

        long[] kinds = new long[3]; // edit, delete, reorder
        AtomicLong entries = new AtomicLong();
        int chunks = Math.min(ids.size(), parallelism * 4);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(parallelism);
            List<Future<long[]>> fs = new ArrayList<>();
            for (int k = 0; k < chunks; k++) {
                int from = (int) ((long) ids.size() * k / chunks), to = (int) ((long) ids.size() * (k + 1) / chunks);
                fs.add(pool.submit(() -> {
                    permits.acquire();
                    try {
                        return verifyRange(ids.subList(from, to));
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (Future<long[]> f : fs) {
                long[] part = f.get();
                for (int i = 0; i < 3; i++) kinds[i] += part[i];
                entries.addAndGet(part[3]);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("chain verification failed", e.getCause());
        }

        Map<String, Long> breaks = new LinkedHashMap<>();
        breaks.put("edit", kinds[0]);
        breaks.put("delete", kinds[1]);
        breaks.put("reorder", kinds[2]);
        double seconds = (System.nanoTime() - t0) / 1e9;
        breaks.forEach(metrics::setChainBreaks);
        metrics.chainEntriesVerified(entries.get());
        metrics.chainVerify(Duration.ofNanos(System.nanoTime() - t0));
        return new Result(entries.get(), breaks, seconds);
    }

    /** returns {edit, delete, reorder, entries} for a range of accounts, on one connection. */
    private long[] verifyRange(List<UUID> ids) throws SQLException {
        long[] out = new long[4];
        try (Connection c = ds.getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false); // required for server-side cursors
            // one snapshot for the whole range, so account heads and entries are consistent while transfers keep committing
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            Map<UUID, byte[]> heads = new java.util.HashMap<>();
            try (PreparedStatement hp = c.prepareStatement("SELECT id, hash_head FROM accounts WHERE id = ANY(?)")) {
                hp.setArray(1, c.createArrayOf("uuid", ids.toArray()));
                try (ResultSet hr = hp.executeQuery()) {
                    while (hr.next()) heads.put(hr.getObject(1, UUID.class), hr.getBytes(2));
                }
            }
            try (PreparedStatement stream = c.prepareStatement("""
                    SELECT txn_id, amount_minor, prev_hash, hash, (extract(epoch FROM created_at) * 1000000)::bigint
                    FROM ledger_entries WHERE account_id = ? ORDER BY created_at, id""");
                 PreparedStatement exists = c.prepareStatement("SELECT 1 FROM ledger_entries WHERE account_id = ? AND hash = ? LIMIT 1")) {
                stream.setFetchSize(FETCH_SIZE);
                for (int i = 0; i < ids.size(); i++) {
                    verifyAccount(ids.get(i), heads.get(ids.get(i)), stream, exists, out);
                }
            }
            c.commit();
        }
        return out;
    }

    private static void verifyAccount(UUID acc, byte[] head, PreparedStatement stream, PreparedStatement exists, long[] out) throws SQLException {
        byte[] expectedPrev = HashChain.GENESIS;
        boolean lastWasReorder = false;
        stream.setObject(1, acc);
        try (ResultSet r = stream.executeQuery()) {
            while (r.next()) {
                byte[] prev = r.getBytes(3), hash = r.getBytes(4);
                boolean reorder = false;
                if (!Arrays.equals(prev, expectedPrev)) {
                    exists.setObject(1, acc);
                    exists.setBytes(2, prev);
                    boolean elsewhere;
                    try (ResultSet e = exists.executeQuery()) { elsewhere = e.next(); }
                    if (elsewhere) {
                        reorder = true;
                        if (!lastWasReorder) out[2]++; // a swapped pair is one incident
                    } else {
                        out[1]++;
                    }
                } else if (!Arrays.equals(HashChain.hash(prev, acc, r.getObject(1, UUID.class), r.getLong(2), r.getLong(5)), hash)) {
                    out[0]++;
                }
                lastWasReorder = reorder;
                expectedPrev = hash;
                out[3]++;
            }
        }
        // the account head must be the hash of the last entry (catches a deleted tail)
        if (!Arrays.equals(head, expectedPrev)) out[1]++;
    }
}
