package com.payflow.ledger.api;

import com.payflow.ledger.ledger.LedgerException;

final class Keys {
    private Keys() {}

    static String check(String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new LedgerException(400, "BAD_IDEMPOTENCY_KEY", "Idempotency-Key must be 1-255 chars");
        }
        return key;
    }
}
