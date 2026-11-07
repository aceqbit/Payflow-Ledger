package com.payflow.ledger.ledger;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-account tamper-evident hash chain with a fixed binary encoding so Java and the
 * Postgres function ledger_entry_hash() produce identical bytes.
 */
public final class HashChain {
    public static final int HASH_LEN = 32;
    /** Genesis prev_hash: 32 zero bytes. */
    public static final byte[] GENESIS = new byte[HASH_LEN];

    private HashChain() {}

    /** Per-thread digest and 80-byte input buffer: the verifier calls this a million times, so nothing is allocated but the result. */
    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });
    private static final ThreadLocal<byte[]> BUF = ThreadLocal.withInitial(() -> new byte[HASH_LEN + 16 + 16 + 8 + 8]);

    public static byte[] hash(byte[] prevHash, UUID accountId, UUID txnId, long amountMinor, long createdAtEpochMicros) {
        if (prevHash.length != HASH_LEN) throw new IllegalArgumentException("prev_hash must be 32 bytes");
        byte[] b = BUF.get();
        System.arraycopy(prevHash, 0, b, 0, HASH_LEN);
        putLong(b, 32, accountId.getMostSignificantBits());
        putLong(b, 40, accountId.getLeastSignificantBits());
        putLong(b, 48, txnId.getMostSignificantBits());
        putLong(b, 56, txnId.getLeastSignificantBits());
        putLong(b, 64, amountMinor);
        putLong(b, 72, createdAtEpochMicros);
        return DIGEST.get().digest(b); // big-endian fixed encoding, identical to ledger_entry_hash() in SQL
    }

    private static void putLong(byte[] b, int off, long v) {
        for (int i = 7; i >= 0; i--) {
            b[off + i] = (byte) v;
            v >>>= 8;
        }
    }

    public static byte[] hash(byte[] prevHash, UUID accountId, UUID txnId, long amountMinor, Instant createdAt) {
        return hash(prevHash, accountId, txnId, amountMinor, toEpochMicros(createdAt));
    }

    public static long toEpochMicros(Instant t) {
        return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000L), t.getNano() / 1_000);
    }
}
