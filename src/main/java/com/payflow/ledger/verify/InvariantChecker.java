package com.payflow.ledger.verify;

import com.payflow.ledger.telemetry.LedgerMetrics;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Money invariants, each one a single SQL statement (one snapshot, so running under load does not
 * produce false positives). Exports ledger_invariant_violations{check} and the last-run timestamp.
 */
@Component
public class InvariantChecker {
    private final JdbcClient jdbc;
    private final LedgerMetrics metrics;

    public InvariantChecker(JdbcClient jdbc, LedgerMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    public Map<String, Long> run() {
        Map<String, Long> r = new LinkedHashMap<>();
        // every transaction's entries must sum to 0 (the global sum is the sum of those)
        r.put("global_sum", count("""
                SELECT count(*) FROM (SELECT txn_id FROM ledger_entries GROUP BY txn_id HAVING sum(amount_minor) <> 0) x"""));
        r.put("negative_balance", count("SELECT count(*) FROM accounts WHERE NOT is_system AND balance_minor < 0"));
        // stored balance must equal the sum of the account's entries
        r.put("balance_mismatch", count("""
                SELECT count(*) FROM accounts a
                LEFT JOIN (SELECT account_id, sum(amount_minor) AS s FROM ledger_entries GROUP BY account_id) e ON e.account_id = a.id
                WHERE a.balance_minor <> COALESCE(e.s, 0)"""));
        // one money operation = one transaction per idempotency key
        r.put("duplicate_charge", count("""
                SELECT count(*) FROM (SELECT 1 FROM transactions WHERE idempotency_key IS NOT NULL
                                      GROUP BY merchant_id, idempotency_key HAVING count(*) > 1) x"""));
        r.forEach(metrics::setInvariantViolations);
        metrics.markInvariantRun(Instant.now().getEpochSecond());
        return r;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
