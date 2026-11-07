package com.payflow.ledger.verify;

import com.payflow.ledger.support.LedgerFixture;
import com.payflow.ledger.support.TestDb;
import com.payflow.ledger.telemetry.LedgerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Each test builds its own accounts, so they share one database but never interfere. */
class VerifierIT {
    static TestDb db;
    static DriverManagerDataSource ds;
    static LedgerMetrics metrics;

    @BeforeAll static void up() {
        db = TestDb.create();
        ds = new DriverManagerDataSource(db.url, TestDb.USER, TestDb.PASSWORD);
        metrics = new LedgerMetrics(new SimpleMeterRegistry());
    }
    @AfterAll static void down() { db.drop(); }

    UUID funded;
    UUID peer;
    UUID sys;

    /** system -> funded (1000), then 10 transfers funded <-> peer so each account has a chain of >= 10 entries. */
    @BeforeEach void seed() throws Exception {
        // isolate tests: wipe with triggers disabled (superuser test DB)
        try (Connection c = db.connect()) {
            LedgerFixture.exec(c, "ALTER TABLE ledger_entries DISABLE TRIGGER USER");
            LedgerFixture.exec(c, "TRUNCATE ledger_entries, transactions, accounts CASCADE");
            LedgerFixture.exec(c, "ALTER TABLE ledger_entries ENABLE TRIGGER USER");
            sys = LedgerFixture.account(c, "m", true);
            funded = LedgerFixture.account(c, "m", false);
            peer = LedgerFixture.account(c, "m", false);
            LedgerFixture.transfer(c, sys, funded, 1000, "fund-" + UUID.randomUUID());
            for (int i = 0; i < 10; i++) {
                LedgerFixture.transfer(c, funded, peer, 10 + i, "k-" + UUID.randomUUID());
                LedgerFixture.transfer(c, peer, funded, 5, "k-" + UUID.randomUUID());
            }
        }
    }

    InvariantChecker inv() { return new InvariantChecker(JdbcClient.create(ds), metrics); }
    ChainVerifier chain() { return new ChainVerifier(ds, metrics, 4); }

    @Test void cleanLedgerHasNoViolationsAndNoBreaks() {
        var r = inv().run();
        assertEquals(0L, r.get("global_sum"));
        assertEquals(0L, r.get("negative_balance"));
        assertEquals(0L, r.get("balance_mismatch"));
        assertEquals(0L, r.get("duplicate_charge"));
        var c = chain().verifyAll();
        assertEquals(0L, c.breaks().values().stream().mapToLong(Long::longValue).sum());
        assertTrue(c.entries() >= 40);
    }

    @Test void brokenBalanceIsDetected() throws Exception {
        try (Connection c = db.connect()) { LedgerFixture.exec(c, "UPDATE accounts SET balance_minor = balance_minor + 1 WHERE id = '" + funded + "'"); }
        assertEquals(1L, inv().run().get("balance_mismatch"));
    }

    @Test void unbalancedTransactionIsDetected() throws Exception {
        try (Connection c = db.connect()) {
            LedgerFixture.exec(c, "ALTER TABLE ledger_entries DISABLE TRIGGER USER");
            LedgerFixture.exec(c, "UPDATE ledger_entries SET amount_minor = amount_minor + 1 WHERE id = (SELECT id FROM ledger_entries WHERE account_id = '" + peer + "' LIMIT 1)");
            LedgerFixture.exec(c, "ALTER TABLE ledger_entries ENABLE TRIGGER USER");
        }
        assertEquals(1L, inv().run().get("global_sum"));
    }

    @Test void negativeBalanceOnNormalAccountIsDetected() throws Exception {
        try (Connection c = db.connect()) {
            LedgerFixture.exec(c, "ALTER TABLE accounts DISABLE TRIGGER USER");
            LedgerFixture.exec(c, "ALTER TABLE accounts DROP CONSTRAINT accounts_balance_nonneg");
            LedgerFixture.exec(c, "UPDATE accounts SET balance_minor = -1 WHERE id = '" + peer + "'");
        }
        assertEquals(1L, inv().run().get("negative_balance"));
        try (Connection c = db.connect()) { // restore the constraint for other tests
            LedgerFixture.exec(c, "UPDATE accounts SET balance_minor = 0 WHERE id = '" + peer + "'");
            LedgerFixture.exec(c, "ALTER TABLE accounts ADD CONSTRAINT accounts_balance_nonneg CHECK (is_system OR balance_minor >= 0)");
        }
    }

    @Test void duplicateChargeIsImpossibleAtTheDatabase() throws Exception {
        try (Connection c = db.connect()) {
            LedgerFixture.transfer(c, funded, peer, 1, "same-key");
            var ex = assertThrows(java.sql.SQLException.class, () -> LedgerFixture.transfer(c, funded, peer, 1, "same-key"));
            assertEquals("23505", ex.getSQLState());
        }
        assertEquals(0L, inv().run().get("duplicate_charge"));
    }

    private void tamper(String sql) throws Exception {
        try (Connection c = db.connect()) {
            LedgerFixture.exec(c, "ALTER TABLE ledger_entries DISABLE TRIGGER USER");
            LedgerFixture.exec(c, sql);
            LedgerFixture.exec(c, "ALTER TABLE ledger_entries ENABLE TRIGGER USER");
        }
    }

    @Test void editedAmountIsDetectedAsEdit() throws Exception {
        tamper("UPDATE ledger_entries SET amount_minor = amount_minor + 7 WHERE id = (SELECT id FROM ledger_entries WHERE account_id = '" + funded + "' ORDER BY created_at, id OFFSET 4 LIMIT 1)");
        var r = chain().verifyAll();
        assertEquals(1L, r.breaks().get("edit"), r.toString());
        assertEquals(0L, r.breaks().get("delete"));
        assertEquals(0L, r.breaks().get("reorder"));
    }

    @Test void deletedMiddleEntryIsDetectedAsDelete() throws Exception {
        tamper("DELETE FROM ledger_entries WHERE id = (SELECT id FROM ledger_entries WHERE account_id = '" + funded + "' ORDER BY created_at, id OFFSET 5 LIMIT 1)");
        var r = chain().verifyAll();
        assertEquals(1L, r.breaks().get("delete"), r.toString());
        assertEquals(0L, r.breaks().get("edit"));
    }

    @Test void deletedLastEntryIsDetectedViaAccountHead() throws Exception {
        tamper("DELETE FROM ledger_entries WHERE id = (SELECT id FROM ledger_entries WHERE account_id = '" + peer + "' ORDER BY created_at DESC, id DESC LIMIT 1)");
        var r = chain().verifyAll();
        assertEquals(1L, r.breaks().get("delete"), r.toString());
    }

    @Test void swappedNeighbourTimestampsAreDetectedAsReorder() throws Exception {
        tamper("""
                WITH two AS (SELECT id, created_at, row_number() OVER (ORDER BY created_at, id) rn
                             FROM ledger_entries WHERE account_id = '%s'),
                     a AS (SELECT id, created_at FROM two WHERE rn = 6), b AS (SELECT id, created_at FROM two WHERE rn = 7)
                UPDATE ledger_entries e SET created_at = CASE WHEN e.id = (SELECT id FROM a) THEN (SELECT created_at FROM b)
                                                              ELSE (SELECT created_at FROM a) END
                WHERE e.id IN (SELECT id FROM a UNION SELECT id FROM b)""".formatted(funded));
        var r = chain().verifyAll();
        assertEquals(1L, r.breaks().get("reorder"), r.toString());
    }

    @Test void gaugesExportBreakCounts() throws Exception {
        tamper("UPDATE ledger_entries SET amount_minor = amount_minor + 1 WHERE id = (SELECT id FROM ledger_entries WHERE account_id = '" + funded + "' LIMIT 1)");
        chain().verifyAll();
        assertEquals(1.0, metricsGauge("ledger.chain.breaks", "kind", "edit"));
    }

    private static double metricsGauge(String name, String tag, String value) {
        return ((SimpleMeterRegistryHolder.registry(metrics)).find(name).tag(tag, value).gauge().value());
    }

    /** LedgerMetrics keeps its registry private; the test built it from a SimpleMeterRegistry we can reach via reflection-free holder. */
    static final class SimpleMeterRegistryHolder {
        static io.micrometer.core.instrument.MeterRegistry registry(LedgerMetrics m) {
            try {
                var f = LedgerMetrics.class.getDeclaredField("registry");
                f.setAccessible(true);
                return (io.micrometer.core.instrument.MeterRegistry) f.get(m);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
