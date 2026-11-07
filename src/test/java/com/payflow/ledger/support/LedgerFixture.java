package com.payflow.ledger.support;

import com.payflow.ledger.ledger.HashChain;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Posts correctly chained transfers straight through SQL, for verifier tests. */
public final class LedgerFixture {
    private LedgerFixture() {}

    public static UUID account(Connection c, String merchant, boolean system) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO accounts (merchant_id, currency, is_system) VALUES (?, 'USD', ?) RETURNING id")) {
            p.setString(1, merchant);
            p.setBoolean(2, system);
            try (ResultSet r = p.executeQuery()) { r.next(); return r.getObject(1, UUID.class); }
        }
    }

    private static byte[] head(Connection c, UUID acc) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT hash_head FROM accounts WHERE id = ?")) {
            p.setObject(1, acc);
            try (ResultSet r = p.executeQuery()) { r.next(); return r.getBytes(1); }
        }
    }

    /** Balanced, hash-chained transfer committed in one transaction. Returns the txn id. */
    public static UUID transfer(Connection c, UUID from, UUID to, long amount, String key) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            UUID txn;
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO transactions (type, merchant_id, idempotency_key) VALUES ('transfer', 'fx', ?) RETURNING id")) {
                p.setString(1, key);
                try (ResultSet r = p.executeQuery()) { r.next(); txn = r.getObject(1, UUID.class); }
            }
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            byte[] fh = HashChain.hash(head(c, from), from, txn, -amount, now);
            byte[] th = HashChain.hash(head(c, to), to, txn, amount, now);
            entry(c, txn, from, -amount, head(c, from), fh, now);
            entry(c, txn, to, amount, head(c, to), th, now);
            update(c, from, -amount, fh);
            update(c, to, amount, th);
            c.commit();
            return txn;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private static void entry(Connection c, UUID txn, UUID acc, long amt, byte[] prev, byte[] hash, Instant at) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("""
                INSERT INTO ledger_entries (txn_id, account_id, amount_minor, prev_hash, hash, created_at) VALUES (?,?,?,?,?,?)""")) {
            p.setObject(1, txn); p.setObject(2, acc); p.setLong(3, amt); p.setBytes(4, prev); p.setBytes(5, hash);
            p.setObject(6, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
            p.executeUpdate();
        }
    }

    private static void update(Connection c, UUID acc, long delta, byte[] hash) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE accounts SET balance_minor = balance_minor + ?, hash_head = ?, version = version + 1 WHERE id = ?")) {
            p.setLong(1, delta); p.setBytes(2, hash); p.setObject(3, acc);
            p.executeUpdate();
        }
    }

    public static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }
}
