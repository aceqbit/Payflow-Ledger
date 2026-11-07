package com.payflow.ledger.ledger;

/** HTTP status + JSON body as stored for idempotent replay. */
public record ApiResult(int status, String body) {}
