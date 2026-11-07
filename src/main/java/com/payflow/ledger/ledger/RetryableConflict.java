package com.payflow.ledger.ledger;

/** A concurrency conflict that is safe to retry (the whole transaction was rolled back). */
public class RetryableConflict extends RuntimeException {
    private final String reason;

    public RetryableConflict(String reason) {
        super(reason, null, false, false);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
