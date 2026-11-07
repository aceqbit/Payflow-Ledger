package com.payflow.ledger.idempotency;

/** The merchant and key of the request currently inside the money transaction (per request thread). */
public final class IdempotencyContext {
    public record Current(String merchantId, String key) {}

    private static final ThreadLocal<Current> CURRENT = new ThreadLocal<>();

    private IdempotencyContext() {}

    public static void set(String merchantId, String key) {
        CURRENT.set(new Current(merchantId, key));
    }

    public static Current get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
