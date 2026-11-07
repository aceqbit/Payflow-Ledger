package com.payflow.ledger.ledger;

import java.util.UUID;

/** merchantId comes from the JWT only. Amounts are BIGINT minor units. */
public record TransferCommand(String merchantId, UUID fromAccountId, UUID toAccountId, long amountMinor, String currency) {}
