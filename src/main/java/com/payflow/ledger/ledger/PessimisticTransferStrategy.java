package com.payflow.ledger.ledger;

import com.payflow.ledger.telemetry.LedgerMetrics;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * FOR UPDATE on both accounts in ascending id order, lock_timeout 2s. Hot accounts (see V9) are handled on a slower
 * path: lock order is always ordinary accounts by id first, then buckets by id, so the order stays globally consistent.
 */
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
        // lockPair skips hot accounts, so a normal transfer needs no extra round trip to discover them
        List<Posting.Acct> locked = posting.lockPair(cmd.merchantId(), cmd.fromAccountId(), cmd.toAccountId());
        metrics.lockWait(NAME, Duration.ofNanos(System.nanoTime() - t0));

        if (locked.size() < 2) {
            return hotPath(cmd, locked);
        }
        Posting.Acct from = Posting.pick(locked, cmd.fromAccountId(), "source");
        Posting.Acct to = Posting.pick(locked, cmd.toAccountId(), "destination");

        if (!from.currency().trim().equals(cmd.currency()) || !to.currency().trim().equals(cmd.currency())) {
            throw new LedgerException(400, "CURRENCY_MISMATCH", "accounts and transfer must share one currency");
        }
        if (!from.system() && from.balanceMinor() < cmd.amountMinor()) {
            return new PostResult.Rejected(409, "INSUFFICIENT_FUNDS", "insufficient funds");
        }
        return new PostResult.Posted(posting.transfer(from, to, cmd.amountMinor()));
    }

    /** At least one side is a hot account (or an account is missing: pick() then reports 404). */
    private PostResult hotPath(TransferCommand cmd, List<Posting.Acct> locked) {
        List<Posting.Acct> heads = posting.readPair(cmd.merchantId(), cmd.fromAccountId(), cmd.toAccountId());
        Posting.Acct fromHead = Posting.pick(heads, cmd.fromAccountId(), "source");
        Posting.Acct toHead = Posting.pick(heads, cmd.toAccountId(), "destination");
        if (!fromHead.currency().trim().equals(cmd.currency()) || !toHead.currency().trim().equals(cmd.currency())) {
            throw new LedgerException(400, "CURRENCY_MISMATCH", "accounts and transfer must share one currency");
        }
        long amount = cmd.amountMinor();
        List<Posting.Leg> legs = new ArrayList<>();
        int buckets = Math.max(fromHead.hotBuckets(), toHead.hotBuckets());
        long t0 = System.nanoTime();

        if (fromHead.hotBuckets() > 0) {
            long remaining = amount;
            for (Posting.Acct b : posting.lockBuckets(cmd.merchantId(), fromHead.id())) {
                if (remaining == 0) break;
                long take = Math.min(b.balanceMinor(), remaining);
                if (take > 0) {
                    legs.add(new Posting.Leg(b, -take));
                    remaining -= take;
                }
            }
            if (remaining > 0) {
                return new PostResult.Rejected(409, "INSUFFICIENT_FUNDS", "insufficient funds");
            }
        } else {
            Posting.Acct from = Posting.pick(locked, fromHead.id(), "source");
            if (!from.system() && from.balanceMinor() < amount) {
                return new PostResult.Rejected(409, "INSUFFICIENT_FUNDS", "insufficient funds");
            }
            legs.add(new Posting.Leg(from, -amount));
        }

        if (toHead.hotBuckets() > 0) {
            legs.add(new Posting.Leg(posting.lockRandomBucket(cmd.merchantId(), toHead.id(), toHead.hotBuckets()), amount));
        } else {
            legs.add(new Posting.Leg(Posting.pick(locked, toHead.id(), "destination"), amount));
        }
        metrics.hotBucketLock(buckets, Duration.ofNanos(System.nanoTime() - t0));
        return new PostResult.Posted(posting.postLegs(legs));
    }
}
