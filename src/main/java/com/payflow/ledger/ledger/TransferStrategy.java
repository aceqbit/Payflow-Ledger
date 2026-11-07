package com.payflow.ledger.ledger;

/** A locking strategy. post() runs inside the money transaction opened by TransferService. */
public interface TransferStrategy {
    String name();

    /** JDBC isolation level (TransactionDefinition.ISOLATION_*). */
    int isolation();

    PostResult post(TransferCommand cmd);
}
