package com.payflow.ledger.payment;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.payflow.ledger.payment.PaymentState.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PaymentStateTest {
    /** The expected table, written out independently of the implementation. */
    public static final Map<PaymentState, Set<PaymentState>> EXPECTED = Map.of(
            CREATED, EnumSet.of(AUTHORIZED),
            AUTHORIZED, EnumSet.of(PARTIALLY_CAPTURED, CAPTURED, VOIDED),
            PARTIALLY_CAPTURED, EnumSet.of(PARTIALLY_CAPTURED, CAPTURED, PARTIALLY_REFUNDED, REFUNDED),
            CAPTURED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            REFUNDED, EnumSet.noneOf(PaymentState.class),
            VOIDED, EnumSet.noneOf(PaymentState.class));

    @Test
    void everyFromToPairMatchesTheTable() {
        int pairs = 0;
        for (PaymentState from : values()) {
            for (PaymentState to : values()) {
                assertEquals(EXPECTED.get(from).contains(to), from.canMoveTo(to), from + " -> " + to);
                pairs++;
            }
        }
        assertEquals(values().length * values().length, pairs);
    }

    @Test
    void terminalStatesAllowNothing() {
        for (PaymentState to : values()) {
            assertEquals(false, REFUNDED.canMoveTo(to));
            assertEquals(false, VOIDED.canMoveTo(to));
        }
    }
}
