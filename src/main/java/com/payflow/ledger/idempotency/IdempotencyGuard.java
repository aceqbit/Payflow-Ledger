package com.payflow.ledger.idempotency;

import com.payflow.ledger.ledger.CountingJdbc;
import com.payflow.ledger.ledger.LedgerException;
import com.payflow.ledger.telemetry.LedgerMetrics;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * INSERT ... ON CONFLICT DO NOTHING inside the money transaction. A concurrent duplicate blocks on the
 * unique index until the first transaction commits, then reads the stored response.
 */
@Component
public class IdempotencyGuard {
    public record Replay(int status, String body) {}

    private static final long WAITED_NANOS = Duration.ofMillis(20).toNanos();

    private final CountingJdbc jdbc;
    private final LedgerMetrics metrics;

    public IdempotencyGuard(CountingJdbc jdbc, LedgerMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    /** Empty = first execution, continue. Present = replay this stored response. Throws 422 on a different body. */
    public Optional<Replay> claim(String merchantId, String key, byte[] requestHash) {
        long t0 = System.nanoTime();
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_keys (merchant_id, key, request_hash) VALUES (:m, :k, :h)
                ON CONFLICT DO NOTHING""")
                .param("m", merchantId).param("k", key).param("h", requestHash).update();
        long elapsed = System.nanoTime() - t0;
        try {
            if (inserted == 1) {
                metrics.idempotency("new");
                return Optional.empty();
            }
            var row = jdbc.sql("SELECT request_hash, response_code, response_body FROM idempotency_keys WHERE merchant_id = :m AND key = :k")
                    .param("m", merchantId).param("k", key)
                    .query((rs, n) -> new Object[]{rs.getBytes(1), (Integer) rs.getObject(2), rs.getString(3)}).single();
            if (!Arrays.equals((byte[]) row[0], requestHash)) {
                metrics.idempotency("mismatch");
                throw new LedgerException(422, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was used with a different request body");
            }
            metrics.idempotency(elapsed >= WAITED_NANOS ? "waited" : "replay");
            return Optional.of(new Replay((Integer) row[1], (String) row[2]));
        } finally {
            metrics.idempotencyGuard(Duration.ofNanos(System.nanoTime() - t0));
        }
    }

    public void store(String merchantId, String key, int status, String body) {
        jdbc.sql("UPDATE idempotency_keys SET response_code = :c, response_body = :b WHERE merchant_id = :m AND key = :k")
                .param("c", status).param("b", body).param("m", merchantId).param("k", key).update();
    }

    public static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
