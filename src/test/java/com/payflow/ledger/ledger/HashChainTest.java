package com.payflow.ledger.ledger;

import com.payflow.ledger.support.TestDb;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HashChainTest {
    static TestDb db;
    static Connection conn;

    @BeforeContainer
    static void up() throws SQLException {
        db = TestDb.create();
        conn = db.connect();
    }

    @AfterContainer
    static void down() throws SQLException {
        conn.close();
        db.drop();
    }

    static byte[] sqlHash(byte[] prev, UUID acc, UUID txn, long amount, long micros) throws SQLException {
        Instant t = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000);
        try (PreparedStatement p = conn.prepareStatement("SELECT ledger_entry_hash(?,?,?,?,?)")) {
            p.setBytes(1, prev); p.setObject(2, acc); p.setObject(3, txn); p.setLong(4, amount);
            p.setObject(5, OffsetDateTime.ofInstant(t, ZoneOffset.UTC));
            ResultSet r = p.executeQuery(); r.next(); return r.getBytes(1);
        }
    }

    @Test
    void genesisIsThirtyTwoZeroBytes() {
        assertEquals(32, HashChain.GENESIS.length);
        for (byte b : HashChain.GENESIS) assertEquals(0, b);
    }

    @Test
    void fixedVectorIsStable() {
        UUID acc = new UUID(0x0102030405060708L, 0x090a0b0c0d0e0f10L);
        UUID txn = new UUID(0x1112131415161718L, 0x191a1b1c1d1e1f20L);
        byte[] h = HashChain.hash(HashChain.GENESIS, acc, txn, -1234L, 1_700_000_000_123_456L);
        assertEquals("e88f88ca635e72a6e367fb1033f435a7ea41a7536ca7d785aba984e628cfdc03", HexFormat.of().formatHex(h), "vector");
    }

    @Test
    void differentAmountChangesHash() {
        UUID a = UUID.randomUUID(), t = UUID.randomUUID();
        assertFalse(java.util.Arrays.equals(
                HashChain.hash(HashChain.GENESIS, a, t, 1, 5L), HashChain.hash(HashChain.GENESIS, a, t, 2, 5L)));
    }

    @Example
    void fixedVectorMatchesSql() throws Exception {
        UUID acc = new UUID(0x0102030405060708L, 0x090a0b0c0d0e0f10L);
        UUID txn = new UUID(0x1112131415161718L, 0x191a1b1c1d1e1f20L);
        assertArrayEquals(HashChain.hash(HashChain.GENESIS, acc, txn, -1234L, 1_700_000_000_123_456L),
                sqlHash(HashChain.GENESIS, acc, txn, -1234L, 1_700_000_000_123_456L));
    }

    @Property
    void javaEqualsSql(@ForAll("prev") byte[] prev, @ForAll long accHi, @ForAll long accLo, @ForAll long txHi, @ForAll long txLo,
                       @ForAll long amount, @ForAll @net.jqwik.api.constraints.LongRange(min = 0, max = 4_000_000_000_000_000L) long micros)
            throws SQLException {
        UUID acc = new UUID(accHi, accLo), txn = new UUID(txHi, txLo);
        assertArrayEquals(HashChain.hash(prev, acc, txn, amount, micros), sqlHash(prev, acc, txn, amount, micros));
    }

    @Provide
    Arbitrary<byte[]> prev() {
        return Arbitraries.bytes().array(byte[].class).ofSize(32);
    }
}
