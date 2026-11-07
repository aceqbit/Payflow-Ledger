package com.payflow.ledger.ledger;

import java.util.UUID;

/** Outcome of posting inside the money transaction. Rejected results are stored for idempotent replay. */
public sealed interface PostResult {
    record Posted(UUID txnId) implements PostResult {}

    record Rejected(int httpStatus, String code, String message) implements PostResult {}
}
