package com.payflow.ledger.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/** merchant_id is deliberately absent: it comes from the JWT only. */
public record TransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @Positive @Max(1_000_000_000_000_000L) long amountMinor,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {}
