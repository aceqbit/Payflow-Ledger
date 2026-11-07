package com.payflow.ledger.ledger;

import java.nio.ByteBuffer;
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

    public static byte[] hash(byte[] prevHash, UUID accountId, UUID txnId, long amountMinor, long createdAtEpochMicros) {
        if (prevHash.length != HASH_LEN) throw new IllegalArgumentException("prev_hash must be 32 bytes");
        ByteBuffer b = ByteBuffer.allocate(HASH_LEN + 16 + 16 + 8 + 8); // big-endian by default
        b.put(prevHash);
        b.putLong(accountId.getMostSignificantBits()).putLong(accountId.getLeastSignificantBits());
        b.putLong(txnId.getMostSignificantBits()).putLong(txnId.getLeastSignificantBits());
        b.putLong(amountMinor);
        b.putLong(createdAtEpochMicros);
        try {
            return MessageDigest.getInstance("SHA-256").digest(b.array());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hash(byte[] prevHash, UUID accountId, UUID txnId, long amountMinor, Instant createdAt) {
        return hash(prevHash, accountId, txnId, amountMinor, toEpochMicros(createdAt));
    }

    public static long toEpochMicros(Instant t) {
        return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000L), t.getNano() / 1_000);
    }
}
