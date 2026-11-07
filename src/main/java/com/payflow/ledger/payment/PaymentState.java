package com.payflow.ledger.payment;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Payment states change only through this transition table. */
public enum PaymentState {
    CREATED, AUTHORIZED, PARTIALLY_CAPTURED, CAPTURED, PARTIALLY_REFUNDED, REFUNDED, VOIDED;

    private static final Map<PaymentState, Set<PaymentState>> ALLOWED = Map.of(
            CREATED, EnumSet.of(AUTHORIZED),
            AUTHORIZED, EnumSet.of(PARTIALLY_CAPTURED, CAPTURED, VOIDED),
            PARTIALLY_CAPTURED, EnumSet.of(PARTIALLY_CAPTURED, CAPTURED, PARTIALLY_REFUNDED, REFUNDED),
            CAPTURED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            REFUNDED, EnumSet.noneOf(PaymentState.class),
            VOIDED, EnumSet.noneOf(PaymentState.class));

    public boolean canMoveTo(PaymentState next) {
        return ALLOWED.get(this).contains(next);
    }
}
