package com.payflow.ledger.ledger;

import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;

import java.util.List;

/** SERIALIZABLE isolation, no manual locks. Postgres aborts conflicting transactions with 40001; we retry. */
@Component
public class SerializableTransferStrategy implements TransferStrategy {
    public static final String NAME = "SERIALIZABLE";

    private final Posting posting;

    public SerializableTransferStrategy(Posting posting) {
        this.posting = posting;
    }

    @Override public String name() { return NAME; }

    @Override public int isolation() { return TransactionDefinition.ISOLATION_SERIALIZABLE; }

    @Override
    public PostResult post(TransferCommand cmd) {
        List<Posting.Acct> accts = posting.readPair(cmd.merchantId(), cmd.fromAccountId(), cmd.toAccountId());
        Posting.Acct from = Posting.pick(accts, cmd.fromAccountId(), "source");
        Posting.Acct to = Posting.pick(accts, cmd.toAccountId(), "destination");
        if (from.hotBuckets() > 0 || to.hotBuckets() > 0) {
            throw new LedgerException(501, "HOT_ACCOUNT_UNSUPPORTED", "hot-account buckets are supported by the PESSIMISTIC strategy only");
        }
        if (!from.currency().trim().equals(cmd.currency()) || !to.currency().trim().equals(cmd.currency())) {
            throw new LedgerException(400, "CURRENCY_MISMATCH", "accounts and transfer must share one currency");
        }
        if (!from.system() && from.balanceMinor() < cmd.amountMinor()) {
            return new PostResult.Rejected(409, "INSUFFICIENT_FUNDS", "insufficient funds");
        }
        return new PostResult.Posted(posting.transfer(from, to, cmd.amountMinor()));
    }
}
