package com.payflow.ledger;

import com.payflow.ledger.ledger.ApiResult;
import com.payflow.ledger.ledger.TransferCommand;
import com.payflow.ledger.ledger.TransferService;
import com.payflow.ledger.support.LedgerFixture;
import com.payflow.ledger.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** 100 threads, one Idempotency-Key, released together by a latch: exactly one charge, 100 identical responses. */
abstract class IdempotencyRaceTests {
    abstract TestDb db();

    @Autowired TransferService transfers;

    long scalar(String sql) throws Exception {
        try (Connection c = db().connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next(); return r.getLong(1);
        }
    }

    @Test void hundredThreadsOneKeyChargeExactlyOnce() throws Exception {
        UUID a, b;
        try (Connection c = db().connect()) {
            UUID sys = LedgerFixture.account(c, "rm", true);
            a = LedgerFixture.account(c, "rm", false);
            b = LedgerFixture.account(c, "rm", false);
            LedgerFixture.transfer(c, sys, a, 10_000, "fund-race");
        }
        long txnsBefore = scalar("SELECT count(*) FROM transactions");
        var cmd = new TransferCommand("rm", a, b, 700, "USD");
        String key = "race-" + UUID.randomUUID();
        int n = 100;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApiResult>> fs = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> { start.await(); return transfers.transfer(cmd, key); }));
            }
            start.countDown();
            List<ApiResult> results = new ArrayList<>();
            for (var f : fs) results.add(f.get());

            assertEquals(n, results.size());
            ApiResult first = results.get(0);
            assertEquals(201, first.status());
            for (ApiResult r : results) {
                assertEquals(first.status(), r.status());
                assertEquals(first.body(), r.body(), "all 100 responses must be identical");
            }
        }
        assertEquals(1, scalar("SELECT count(*) FROM transactions") - txnsBefore, "exactly one transaction");
        assertEquals(9_300, scalar("SELECT balance_minor FROM accounts WHERE id = '" + a + "'"));
        assertEquals(700, scalar("SELECT balance_minor FROM accounts WHERE id = '" + b + "'"));
        assertEquals(1, scalar("SELECT count(*) FROM idempotency_keys WHERE merchant_id = 'rm' AND key = '" + key + "'"));
    }
}
