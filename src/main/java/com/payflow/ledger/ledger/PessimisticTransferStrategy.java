package com.payflow.ledger.ledger;

import com.payflow.ledger.telemetry.LedgerMetrics;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;

import java.time.Duration;
import java.util.List;

/** FOR UPDATE on both accounts in ascending id order, lock_timeout 2s. */
@Component
public class PessimisticTransferStrategy implements TransferStrategy {
    public static final String NAME = "PESSIMISTIC";

    private final CountingJdbc jdbc;
    private final Posting posting;
    private final LedgerMetrics metrics;

    public PessimisticTransferStrategy(CountingJdbc jdbc, Posting posting, LedgerMetrics metrics) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.metrics = metrics;
    }

    @Override public String name() { return NAME; }

    @Override public int isolation() { return TransactionDefinition.ISOLATION_READ_COMMITTED; }

    @Override
    public PostResult post(TransferCommand cmd) {
        jdbc.sql("SET LOCAL lock_timeout = '2s'").update();

        long t0 = System.nanoTime();
        List<Posting.Acct> locked = jdbc.sql("""
                SELECT id, currency, balance_minor, is_system, hash_head FROM accounts
                WHERE id IN (:a, :b) AND merchant_id = :m ORDER BY id FOR UPDATE""")
                .param("a", cmd.fromAccountId()).param("b", cmd.toAccountId()).param("m", cmd.merchantId())
                .query((rs, n) -> new Posting.Acct(rs.getObject("id", java.util.UUID.class), rs.getString("currency"),
                        rs.getLong("balance_minor"), rs.getBoolean("is_system"), rs.getBytes("hash_head")))
                .list();
        metrics.lockWait(NAME, Duration.ofNanos(System.nanoTime() - t0));

        Posting.Acct from = locked.stream().filter(a -> a.id().equals(cmd.fromAccountId())).findFirst()
                .orElseThrow(() -> new LedgerException(404, "ACCOUNT_NOT_FOUND", "source account not found"));
        Posting.Acct to = locked.stream().filter(a -> a.id().equals(cmd.toAccountId())).findFirst()
                .orElseThrow(() -> new LedgerException(404, "ACCOUNT_NOT_FOUND", "destination account not found"));

        if (!from.currency().trim().equals(cmd.currency()) || !to.currency().trim().equals(cmd.currency())) {
            throw new LedgerException(400, "CURRENCY_MISMATCH", "accounts and transfer must share one currency");
        }
        if (!from.system() && from.balanceMinor() < cmd.amountMinor()) {
            return new PostResult.Rejected(409, "INSUFFICIENT_FUNDS", "insufficient funds");
        }
        return new PostResult.Posted(posting.transfer(from, to, cmd.amountMinor()));
    }
}
