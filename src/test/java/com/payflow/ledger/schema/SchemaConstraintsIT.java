package com.payflow.ledger.schema;

import com.payflow.ledger.support.TestDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

class SchemaConstraintsIT {
    static TestDb db;
    static final byte[] H = new byte[32];

    @BeforeAll static void up() { db = TestDb.create(); }
    @AfterAll static void down() { db.drop(); }

    static String sqlState(Callable<?> c) {
        try { c.call(); } catch (SQLException e) { return e.getSQLState(); } catch (Exception e) { throw new RuntimeException(e); }
        return "NONE";
    }

    static UUID account(Connection c, long balance, boolean system) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO accounts (merchant_id, currency, balance_minor, is_system) VALUES ('m1','USD',?,?) RETURNING id")) {
            p.setLong(1, balance); p.setBoolean(2, system);
            ResultSet r = p.executeQuery(); r.next(); return r.getObject(1, UUID.class);
        }
    }

    static UUID txn(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("INSERT INTO transactions (type) VALUES ('t') RETURNING id")) {
            r.next(); return r.getObject(1, UUID.class);
        }
    }

    static void entry(Connection c, UUID txn, UUID acc, long amt) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO ledger_entries (txn_id, account_id, amount_minor, prev_hash, hash) VALUES (?,?,?,?,?)")) {
            p.setObject(1, txn); p.setObject(2, acc); p.setLong(3, amt); p.setBytes(4, H); p.setBytes(5, H);
            p.executeUpdate();
        }
    }

    static String pay(String state, long auth, long cap, long ref) throws Exception {
        try (Connection c = db.connect()) {
            UUID a = account(c, 0, true), b = account(c, 0, true);
            return "INSERT INTO payments (merchant_id,currency,state,authorized,captured,refunded,payer_account_id,payee_account_id,amount_minor) VALUES ('m','USD','"
                    + state + "'," + auth + "," + cap + "," + ref + ",'" + a + "','" + b + "',100)";
        }
    }

    @Test void negativeBalanceRejectedForNormalAccount() throws Exception {
        try (Connection c = db.connect()) { assertEquals("23514", sqlState(() -> account(c, -1, false))); }
    }

    @Test void systemAccountMayGoNegative() throws Exception {
        try (Connection c = db.connect()) { assertNotNull(account(c, -500, true)); }
    }

    @Test void currencyMustBeThreeChars() throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            assertEquals("22001", sqlState(() -> s.execute("INSERT INTO accounts (merchant_id, currency) VALUES ('m','USDX')")));
        }
    }

    @Test void capturedCannotExceedAuthorized() throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            assertEquals("23514", sqlState(() -> s.execute(pay("CAPTURED", 100, 101, 0))));
        }
    }

    @Test void refundedCannotExceedCaptured() throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            assertEquals("23514", sqlState(() -> s.execute(pay("REFUNDED", 100, 50, 60))));
        }
    }

    @Test void validPaymentAccepted() throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            assertEquals(1, s.executeUpdate(pay("PARTIALLY_REFUNDED", 100, 50, 10)));
        }
    }

    @Test void unbalancedTransactionRejectedAtCommit() throws Exception {
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            UUID a = account(c, 0, true), t = txn(c);
            entry(c, t, a, 100);
            assertEquals("23514", sqlState(() -> { c.commit(); return null; }));
            c.rollback();
        }
    }

    @Test void balancedTransactionCommits() throws Exception {
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            UUID a = account(c, 0, true), b = account(c, 0, true), t = txn(c);
            entry(c, t, a, -100); entry(c, t, b, 100);
            c.commit();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM ledger_entries WHERE txn_id='" + t + "'")) {
                r.next(); assertEquals(2, r.getInt(1));
            }
        }
    }

    @Test void zeroAmountEntryRejected() throws Exception {
        try (Connection c = db.connect()) {
            UUID a = account(c, 0, true); UUID t = txn(c);
            assertEquals("23514", sqlState(() -> { entry(c, t, a, 0); return null; }));
        }
    }

    @Test void entriesAreAppendOnly() throws Exception {
        try (Connection c = db.connect()) {
            c.setAutoCommit(false);
            UUID a = account(c, 0, true), b = account(c, 0, true), t = txn(c);
            entry(c, t, a, -5); entry(c, t, b, 5);
            c.commit();
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                assertEquals("23000", sqlState(() -> s.executeUpdate("UPDATE ledger_entries SET amount_minor = 9 WHERE txn_id='" + t + "'")));
                assertEquals("23000", sqlState(() -> s.executeUpdate("DELETE FROM ledger_entries WHERE txn_id='" + t + "'")));
            }
        }
    }

    @Test void entryNeedsExistingAccount() throws Exception {
        try (Connection c = db.connect()) {
            UUID t = txn(c);
            assertEquals("23503", sqlState(() -> { entry(c, t, UUID.randomUUID(), 1); return null; }));
        }
    }

    @Test void idempotencyKeyIsUniquePerMerchant() throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            String ins = "INSERT INTO idempotency_keys (merchant_id,key,request_hash) VALUES ('m','k1',decode('01','hex'))";
            assertEquals(1, s.executeUpdate(ins));
            assertEquals("23505", sqlState(() -> s.executeUpdate(ins)));
            assertEquals(0, s.executeUpdate(ins + " ON CONFLICT DO NOTHING"));
            assertEquals(1, s.executeUpdate("INSERT INTO idempotency_keys (merchant_id,key,request_hash) VALUES ('other','k1',decode('01','hex'))"));
        }
    }

    @Test void outboxDefaultsFollowContract() throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            ResultSet r = s.executeQuery("INSERT INTO outbox (aggregate_type,aggregate_id,type,seq,merchant_id,amount_minor,currency,payload,traceparent) "
                    + "VALUES ('payment','p1','PaymentAuthorized',1,'m',10,'USD','{}','00-aa-bb-01') "
                    + "RETURNING uuid_extract_version(id), occurred_at IS NOT NULL, traceparent");
            r.next();
            assertEquals(7, r.getInt(1));
            assertTrue(r.getBoolean(2));
            assertEquals("00-aa-bb-01", r.getString(3));
        }
    }

    @Test void outboxSeqUniquePerAggregate() throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            String ins = "INSERT INTO outbox (aggregate_type,aggregate_id,type,seq,merchant_id,amount_minor,currency,payload) "
                    + "VALUES ('transfer','x','TransferPosted',1,'m',10,'USD','{}')";
            s.executeUpdate(ins);
            assertEquals("23505", sqlState(() -> s.executeUpdate(ins)));
        }
    }
}
