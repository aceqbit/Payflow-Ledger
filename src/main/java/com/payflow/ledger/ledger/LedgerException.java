package com.payflow.ledger.ledger;

/** Business failure that rolls the transaction back and is not stored for replay. */
public class LedgerException extends RuntimeException {
    private final int httpStatus;
    private final String code;

    public LedgerException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }
}
