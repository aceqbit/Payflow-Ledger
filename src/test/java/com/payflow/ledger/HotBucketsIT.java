package com.payflow.ledger;

import com.payflow.ledger.ledger.ApiResult;
import com.payflow.ledger.ledger.TransferCommand;
import com.payflow.ledger.ledger.TransferService;
import com.payflow.ledger.support.LedgerFixture;
import com.payflow.ledger.support.SpringDbTest;
import com.payflow.ledger.support.TestDb;
import com.payflow.ledger.verify.ChainVerifier;
import com.payflow.ledger.verify.InvariantChecker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Hot-account buckets: money conserved, balance = sum of buckets, per-bucket chains intact, invariants 0. */
@SpringBootTest(properties = "ledger.strategy=PESSIMISTIC")
class HotBucketsIT {
    static TestDb db;

    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        if (db == null) db = TestDb.create();
        SpringDbTest.wire(r, db);
    }
    @AfterAll static void down() { db.drop(); }

    @Autowired TransferService transfers;
    @Autowired InvariantChecker invariants;
    @Autowired ChainVerifier chain;

    static UUID hot, a, b;

    @BeforeAll static void setup() throws Exception {
        if (db == null) db = TestDb.create();
        try (Connection c = db.connect()) {
            LedgerFixture.exec(c, Files.readString(Path.of("perf", "hot.sql")));
            UUID sys = LedgerFixture.account(c, "hm", true);
            hot = LedgerFixture.account(c, "hm", false);
            a = LedgerFixture.account(c, "hm", false);
            b = LedgerFixture.account(c, "hm", false);
            LedgerFixture.transfer(c, sys, hot, 1000, "fund-hot");
            LedgerFixture.transfer(c, sys, a, 100_000, "fund-a");
            LedgerFixture.transfer(c, sys, b, 100_000, "fund-b");
            LedgerFixture.exec(c, "SELECT make_hot('" + hot + "', 4)");
        }
    }

    long scalar(String sql) throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next(); return r.getLong(1);
        }
    }

    long hotBalance() throws Exception {
        return scalar("SELECT coalesce(sum(balance_minor),0) FROM accounts WHERE parent_id = '" + hot + "'");
    }

    ApiResult pay(UUID from, UUID to, long amt) {
        return transfers.transfer(new TransferCommand("hm", from, to, amt, "USD"), UUID.randomUUID().toString());
    }

    @Test void setupMovedTheBalanceIntoFourBuckets() throws Exception {
        assertEquals(4, scalar("SELECT count(*) FROM accounts WHERE parent_id = '" + hot + "'"));
        assertEquals(0, scalar("SELECT balance_minor FROM accounts WHERE id = '" + hot + "'"));
        assertEquals(4, scalar("SELECT hot_buckets FROM accounts WHERE id = '" + hot + "'"));
    }

    @Test void creditsLandInOneBucketAndDebitsSpanBuckets() throws Exception {
        long before = hotBalance();
        assertEquals(201, pay(a, hot, 100).status());
        assertEquals(before + 100, hotBalance());
        assertEquals(0, scalar("SELECT balance_minor FROM accounts WHERE id = '" + hot + "'"), "parent keeps no balance");

        // buckets hold ~250 each, so this debit must pull from several buckets
        long bal = hotBalance();
        assertEquals(201, pay(hot, b, bal - 10).status());
        assertEquals(10, hotBalance());
        assertEquals(409, pay(hot, b, 11).status(), "insufficient funds across all buckets");
        assertEquals(10, hotBalance());
        var inv = invariants.run();
        assertEquals(0L, inv.get("balance_mismatch"), inv.toString());
        assertEquals(0L, inv.get("global_sum"));
        assertEquals(0L, chain.verifyAll().breaks().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(201, pay(b, hot, bal - 10).status()); // put the money back for the other tests
    }

    @Test void concurrentTrafficAgainstTheHotAccountKeepsEveryInvariant() throws Exception {
        AtomicLong unexpected = new AtomicLong();
        long deadline = System.nanoTime() + 8_000_000_000L;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 100; t++) {
                fs.add(pool.submit(() -> {
                    var r = ThreadLocalRandom.current();
                    UUID[] all = {hot, a, b};
                    while (System.nanoTime() < deadline) {
                        UUID from = all[r.nextInt(3)], to = all[r.nextInt(3)];
                        if (from.equals(to)) continue;
                        try {
                            int st = pay(from, to, 1 + r.nextInt(50)).status();
                            if (st != 201 && st != 409) unexpected.incrementAndGet();
                        } catch (DataAccessException | com.payflow.ledger.ledger.LedgerException e) {
                            if (!(e instanceof com.payflow.ledger.ledger.LedgerException le && le.httpStatus() == 503)) unexpected.incrementAndGet();
                        }
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        assertEquals(0, unexpected.get());
        assertEquals(0, scalar("SELECT balance_minor FROM accounts WHERE id = '" + hot + "'"));
        var inv = invariants.run();
        assertEquals(0L, inv.get("global_sum"), inv.toString());
        assertEquals(0L, inv.get("negative_balance"), inv.toString());
        assertEquals(0L, inv.get("balance_mismatch"), inv.toString());
        assertEquals(0L, inv.get("duplicate_charge"), inv.toString());
        var c = chain.verifyAll();
        assertEquals(0L, c.breaks().values().stream().mapToLong(Long::longValue).sum(), c.toString());
    }
}
