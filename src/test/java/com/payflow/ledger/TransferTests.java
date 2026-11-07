package com.payflow.ledger;

import com.payflow.ledger.support.TestDb;
import com.payflow.ledger.support.TestTokens;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

abstract class TransferTests {
    abstract TestDb dbRef();
    abstract double expectedRoundTrips();
    static final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired MeterRegistry registry;

    UUID account(String merchant, long balance) throws Exception {
        try (Connection c = dbRef().connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("INSERT INTO accounts (merchant_id,currency,balance_minor) VALUES ('" + merchant + "','USD'," + balance + ") RETURNING id")) {
            r.next(); return r.getObject(1, UUID.class);
        }
    }

    HttpResponse<String> post(String merchant, String key, UUID from, UUID to, long amount) throws Exception {
        String body = "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to + "\",\"amountMinor\":" + amount + ",\"currency\":\"USD\",\"merchantId\":\"evil\"}";
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/transfers"))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (merchant != null) b.header("Authorization", "Bearer " + TestTokens.forMerchant(merchant));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    long scalar(String sql) throws Exception {
        try (Connection c = dbRef().connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next(); return r.getLong(1);
        }
    }

    @Test void happyPathMovesMoneyAndWritesEverythingInOneTransaction() throws Exception {
        String m = "m-" + UUID.randomUUID();
        UUID a = account(m, 1000), b = account(m, 0);
        var r = post(m, UUID.randomUUID().toString(), a, b, 300);
        assertEquals(201, r.statusCode(), r.body());
        assertEquals(700, scalar("SELECT balance_minor FROM accounts WHERE id='" + a + "'"));
        assertEquals(300, scalar("SELECT balance_minor FROM accounts WHERE id='" + b + "'"));
        assertEquals(0, scalar("SELECT sum(amount_minor) FROM ledger_entries WHERE account_id IN ('" + a + "','" + b + "')"));
        assertEquals(2, scalar("SELECT count(*) FROM ledger_entries WHERE account_id IN ('" + a + "','" + b + "')"));
        // chain head of each account is the hash of its last entry
        assertEquals(2, scalar("SELECT count(*) FROM accounts ac JOIN ledger_entries e ON e.account_id=ac.id AND e.hash=ac.hash_head WHERE ac.id IN ('" + a + "','" + b + "')"));
        assertEquals(1, scalar("SELECT count(*) FROM outbox WHERE merchant_id='" + m + "' AND type='TransferPosted'"));
    }

    @Test void replayReturnsIdenticalStatusAndBodyAndPostsOnce() throws Exception {
        String m = "m-" + UUID.randomUUID(), key = UUID.randomUUID().toString();
        UUID a = account(m, 1000), b = account(m, 0);
        var first = post(m, key, a, b, 100);
        var second = post(m, key, a, b, 100);
        assertEquals(201, first.statusCode());
        assertEquals(first.statusCode(), second.statusCode());
        assertEquals(first.body(), second.body());
        assertEquals(900, scalar("SELECT balance_minor FROM accounts WHERE id='" + a + "'"));
        assertEquals(2, scalar("SELECT count(*) FROM ledger_entries WHERE account_id IN ('" + a + "','" + b + "')"));
    }

    @Test void sameKeyDifferentBodyIs422() throws Exception {
        String m = "m-" + UUID.randomUUID(), key = UUID.randomUUID().toString();
        UUID a = account(m, 1000), b = account(m, 0);
        assertEquals(201, post(m, key, a, b, 100).statusCode());
        assertEquals(422, post(m, key, a, b, 101).statusCode());
        assertEquals(900, scalar("SELECT balance_minor FROM accounts WHERE id='" + a + "'"));
    }

    @Test void insufficientFundsIs409AndReplaysTheSame409() throws Exception {
        String m = "m-" + UUID.randomUUID(), key = UUID.randomUUID().toString();
        UUID a = account(m, 50), b = account(m, 0);
        var r1 = post(m, key, a, b, 100);
        assertEquals(409, r1.statusCode());
        assertTrue(r1.body().contains("INSUFFICIENT_FUNDS"));
        var r2 = post(m, key, a, b, 100);
        assertEquals(409, r2.statusCode());
        assertEquals(r1.body(), r2.body());
        assertEquals(0, scalar("SELECT count(*) FROM ledger_entries WHERE account_id IN ('" + a + "','" + b + "')"));
    }

    @Test void outboxCarriesTraceparent() throws Exception {
        String m = "m-" + UUID.randomUUID();
        UUID a = account(m, 10), b = account(m, 0);
        assertEquals(201, post(m, UUID.randomUUID().toString(), a, b, 1).statusCode());
        try (Connection c = dbRef().connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT traceparent FROM outbox WHERE merchant_id='" + m + "'")) {
            r.next();
            assertNotNull(r.getString(1), "outbox row must carry the request span's traceparent");
            assertTrue(r.getString(1).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"));
        }
    }

    @Test void roundTripsAreCountedPerTransfer() throws Exception {
        String m = "m-" + UUID.randomUUID();
        UUID a = account(m, 10), b = account(m, 0);
        var summary = registry.find("ledger.db.roundtrips").tag("operation", "transfer");
        long before = summary.summary() == null ? 0 : summary.summary().count();
        assertEquals(201, post(m, UUID.randomUUID().toString(), a, b, 1).statusCode());
        var s = registry.find("ledger.db.roundtrips").tag("operation", "transfer").summary();
        assertEquals(before + 1, s.count());
        assertEquals(expectedRoundTrips(), s.max(), "statements per transfer");
    }

    @Test void metricsCarryStrategyAndOutcomeLabels() throws Exception {
        String m = "m-" + UUID.randomUUID();
        UUID a = account(m, 10), b = account(m, 0);
        assertEquals(201, post(m, UUID.randomUUID().toString(), a, b, 1).statusCode());
        assertNotNull(registry.find("ledger.transfers").tag("strategy", "PESSIMISTIC").tag("outcome", "ok").counter());
        assertNotNull(registry.find("ledger.transfer.duration").tag("strategy", "PESSIMISTIC").timer());
        assertNotNull(registry.find("ledger.idempotency").tag("result", "new").counter());
    }

    @Test void otherMerchantsAccountIsNotFoundAndBodyMerchantIsIgnored() throws Exception {
        String m = "m-" + UUID.randomUUID(), other = "o-" + UUID.randomUUID();
        UUID a = account(m, 100), b = account(m, 0);
        assertEquals(404, post(other, UUID.randomUUID().toString(), a, b, 10).statusCode());
        assertEquals(100, scalar("SELECT balance_minor FROM accounts WHERE id='" + a + "'"));
    }

    @Test void noTokenIs401() throws Exception {
        assertEquals(401, post(null, "k", UUID.randomUUID(), UUID.randomUUID(), 1).statusCode());
    }
}
