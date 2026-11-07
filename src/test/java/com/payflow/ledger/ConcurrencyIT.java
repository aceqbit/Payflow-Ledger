package com.payflow.ledger;

import com.payflow.ledger.ledger.LedgerException;
import com.payflow.ledger.ledger.TransferCommand;
import com.payflow.ledger.ledger.TransferService;
import com.payflow.ledger.support.LedgerFixture;
import com.payflow.ledger.support.SpringDbTest;
import com.payflow.ledger.support.TestDb;
import com.payflow.ledger.verify.ChainVerifier;
import com.payflow.ledger.verify.InvariantChecker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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

/** 500 virtual threads doing random transfers among 50 accounts. Asserts invariants, never timings. */
@SpringBootTest
class ConcurrencyIT {
    static TestDb db;

    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        if (db == null) db = TestDb.create();
        SpringDbTest.wire(r, db);
    }
    @AfterAll static void down() { db.drop(); }

    @Autowired TransferService transfers;
    @Autowired InvariantChecker invariants;
    @Autowired ChainVerifier chain;

    long deadlocks() throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()")) {
            r.next(); return r.getLong(1);
        }
    }

    @Test void fiveHundredThreadsKeepEveryInvariant() throws Exception {
        int seconds = Integer.getInteger("concurrency.seconds", 30);
        List<UUID> accounts = new ArrayList<>();
        try (Connection c = db.connect()) {
            UUID sys = LedgerFixture.account(c, "cm", true);
            for (int i = 0; i < 50; i++) {
                UUID a = LedgerFixture.account(c, "cm", false);
                LedgerFixture.transfer(c, sys, a, 10_000, "fund-" + i);
                accounts.add(a);
            }
        }
        long deadlocksBefore = deadlocks();
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        AtomicLong ok = new AtomicLong(), insufficient = new AtomicLong(), lockTimeouts = new AtomicLong(), other = new AtomicLong();
        List<Throwable> unexpected = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 500; t++) {
                fs.add(pool.submit(() -> {
                    var rnd = ThreadLocalRandom.current();
                    while (System.nanoTime() < deadline) {
                        UUID from = accounts.get(rnd.nextInt(accounts.size())), to = accounts.get(rnd.nextInt(accounts.size()));
                        if (from.equals(to)) continue;
                        try {
                            var r = transfers.transfer(new TransferCommand("cm", from, to, 1 + rnd.nextInt(2_000), "USD"), UUID.randomUUID().toString());
                            if (r.status() == 201) ok.incrementAndGet(); else if (r.status() == 409) insufficient.incrementAndGet(); else other.incrementAndGet();
                        } catch (DataAccessException e) {
                            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("lock timeout")) lockTimeouts.incrementAndGet();
                            else synchronized (unexpected) { unexpected.add(e); }
                        } catch (LedgerException e) {
                            synchronized (unexpected) { unexpected.add(e); }
                        }
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        }

        assertTrue(unexpected.isEmpty(), "unexpected failures: " + unexpected.stream().limit(3).toList());
        assertEquals(0, other.get());
        assertTrue(ok.get() > 1000, "expected real throughput, got " + ok.get());
        assertEquals(0, deadlocks() - deadlocksBefore, "ordered locks must never deadlock");

        var inv = invariants.run();
        assertEquals(0L, inv.get("global_sum"), inv.toString());
        assertEquals(0L, inv.get("negative_balance"), inv.toString());
        assertEquals(0L, inv.get("balance_mismatch"), inv.toString());
        assertEquals(0L, inv.get("duplicate_charge"), inv.toString());
        var c = chain.verifyAll();
        assertEquals(0L, c.breaks().values().stream().mapToLong(Long::longValue).sum(), c.toString());
        System.out.printf("ConcurrencyIT: ok=%d insufficient=%d lockTimeouts=%d entries=%d%n", ok.get(), insufficient.get(), lockTimeouts.get(), c.entries());
    }
}
