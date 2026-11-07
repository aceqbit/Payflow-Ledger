package com.payflow.ledger.ledger;

import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

/** Max 5 attempts, exponential backoff with full jitter starting at 2 ms. Shared by every strategy. */
@Component
public class RetryPolicy {
    public static final int MAX_ATTEMPTS = 5;
    private static final long BASE_NANOS = 2_000_000L;
    private static final long CAP_NANOS = 100_000_000L;

    /** SQLSTATE of the nearest SQLException in the cause chain, or null. */
    public static String sqlStateOf(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException s && s.getSQLState() != null) return s.getSQLState();
        }
        return null;
    }

    /** Retry reason label (version_conflict|serialization_failure|deadlock|lock_timeout) or null when not retryable. */
    public String reasonOf(Throwable t) {
        if (t instanceof RetryableConflict c) return c.reason();
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException s && s.getSQLState() != null) {
                switch (s.getSQLState()) {
                    case "40001": return "serialization_failure";
                    case "40P01": return "deadlock";
                    case "55P03": return "lock_timeout";
                    default: break;
                }
            }
        }
        return null;
    }

    /** Full jitter: uniform in [0, min(cap, base * 2^(attempt-1))]. attempt is the one that just failed (1-based). */
    public long backoffNanos(int attempt) {
        long ceiling = Math.min(CAP_NANOS, BASE_NANOS << Math.min(attempt - 1, 20));
        return ThreadLocalRandom.current().nextLong(ceiling + 1);
    }

    public void sleep(int attempt) {
        try {
            Thread.sleep(java.time.Duration.ofNanos(backoffNanos(attempt)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off", e);
        }
    }
}
