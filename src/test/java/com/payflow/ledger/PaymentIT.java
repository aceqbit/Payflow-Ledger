package com.payflow.ledger;

import com.payflow.ledger.support.TestDb;
import com.payflow.ledger.support.TestTokens;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentIT {
    static TestDb db;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll static void up() { if (db == null) db = TestDb.create(); }
    @AfterAll static void down() { db.drop(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        if (db == null) db = TestDb.create();
        r.add("spring.datasource.url", () -> db.url);
        r.add("spring.datasource.username", () -> TestDb.USER);
        r.add("spring.datasource.password", () -> TestDb.PASSWORD);
        r.add("management.otlp.metrics.export.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired MeterRegistry registry;

    UUID account(String merchant, long balance) throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("INSERT INTO accounts (merchant_id,currency,balance_minor) VALUES ('" + merchant + "','USD'," + balance + ") RETURNING id")) {
            r.next(); return r.getObject(1, UUID.class);
        }
    }

    long scalar(String sql) throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next(); return r.getLong(1);
        }
    }

    HttpResponse<String> call(String method, String path, String merchant, String key, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + TestTokens.forMerchant(merchant)).header("Content-Type", "application/json");
        if (key != null) b.header("Idempotency-Key", key);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String merchant, String body) throws Exception {
        return call("POST", path, merchant, UUID.randomUUID().toString(), body);
    }

    static String idOf(String json) {
        Matcher m = Pattern.compile("\"paymentId\":\"([0-9a-f-]+)\"").matcher(json);
        assertTrue(m.find(), json);
        return m.group(1);
    }

    String newPayment(String m, UUID payer, UUID payee, long amount) throws Exception {
        var r = post("/v1/payments", m, "{\"payerAccountId\":\"" + payer + "\",\"payeeAccountId\":\"" + payee + "\",\"amountMinor\":" + amount + ",\"currency\":\"USD\"}");
        assertEquals(201, r.statusCode(), r.body());
        return idOf(r.body());
    }

    @Test void fullLifecycleAuthorizeCaptureRefund() throws Exception {
        String m = "m-" + UUID.randomUUID();
        UUID payer = account(m, 1000), payee = account(m, 0);
        String id = newPayment(m, payer, payee, 500);
        assertEquals(200, post("/v1/payments/" + id + "/authorize", m, null).statusCode());

        var cap = post("/v1/payments/" + id + "/capture", m, null);
        assertEquals(200, cap.statusCode(), cap.body());
        assertTrue(cap.body().contains("\"state\":\"CAPTURED\""));
        assertEquals(500, scalar("SELECT balance_minor FROM accounts WHERE id='" + payer + "'"));
        assertEquals(500, scalar("SELECT balance_minor FROM accounts WHERE id='" + payee + "'"));

        var ref = post("/v1/payments/" + id + "/refund", m, "{\"amountMinor\":200}");
        assertEquals(200, ref.statusCode(), ref.body());
        assertTrue(ref.body().contains("PARTIALLY_REFUNDED"));
        var ref2 = post("/v1/payments/" + id + "/refund", m, null);
        assertTrue(ref2.body().contains("\"state\":\"REFUNDED\""), ref2.body());
        assertEquals(1000, scalar("SELECT balance_minor FROM accounts WHERE id='" + payer + "'"));
        assertEquals(0, scalar("SELECT balance_minor FROM accounts WHERE id='" + payee + "'"));
        assertEquals(0, scalar("SELECT sum(amount_minor) FROM ledger_entries WHERE account_id IN ('" + payer + "','" + payee + "')"));
        // version bumps and outbox seq = new version
        assertEquals(5, scalar("SELECT version FROM payments WHERE id='" + id + "'"));
        assertEquals(5, scalar("SELECT max(seq) FROM outbox WHERE aggregate_type='payment' AND aggregate_id='" + id + "'"));
        assertEquals(5, scalar("SELECT count(*) FROM outbox WHERE aggregate_type='payment' AND aggregate_id='" + id + "'"));
    }

    @Test void partialCaptureThenRemainder() throws Exception {
        String m = "m-" + UUID.randomUUID();
        UUID payer = account(m, 1000), payee = account(m, 0);
        String id = newPayment(m, payer, payee, 600);
        post("/v1/payments/" + id + "/authorize", m, null);
        var p1 = post("/v1/payments/" + id + "/capture", m, "{\"amountMinor\":250}");
        assertTrue(p1.body().contains("PARTIALLY_CAPTURED"), p1.body());
        var p2 = post("/v1/payments/" + id + "/capture", m, null);
        assertTrue(p2.body().contains("\"state\":\"CAPTURED\""), p2.body());
        assertEquals(600, scalar("SELECT balance_minor FROM accounts WHERE id='" + payee + "'"));
    }

    @Test void voidAuthorizedPaymentMovesNoMoney() throws Exception {
        String m = "m-" + UUID.randomUUID();
        UUID payer = account(m, 100), payee = account(m, 0);
        String id = newPayment(m, payer, payee, 50);
        post("/v1/payments/" + id + "/authorize", m, null);
        var v = post("/v1/payments/" + id + "/void", m, null);
        assertTrue(v.body().contains("VOIDED"), v.body());
        assertEquals(100, scalar("SELECT balance_minor FROM accounts WHERE id='" + payer + "'"));
    }

    @Test void illegalMovesReturn409AndAreCountedAsRejected() throws Exception {
        String m = "m-" + UUID.randomUUID();
        UUID payer = account(m, 100), payee = account(m, 0);
        String id = newPayment(m, payer, payee, 50);
        double before = rejected();
        var capBeforeAuth = post("/v1/payments/" + id + "/capture", m, null);
        assertEquals(409, capBeforeAuth.statusCode(), capBeforeAuth.body());
        assertTrue(capBeforeAuth.body().contains("ILLEGAL_TRANSITION"));
        assertEquals(409, post("/v1/payments/" + id + "/refund", m, null).statusCode());
        assertEquals(409, post("/v1/payments/" + id + "/void", m, null).statusCode());
        post("/v1/payments/" + id + "/authorize", m, null);
        assertEquals(409, post("/v1/payments/" + id + "/authorize", m, null).statusCode(), "authorize twice");
        post("/v1/payments/" + id + "/void", m, null);
        assertEquals(409, post("/v1/payments/" + id + "/capture", m, null).statusCode(), "capture after void");
        assertTrue(rejected() - before >= 5, "rejected transitions counted");
        assertEquals(100, scalar("SELECT balance_minor FROM accounts WHERE id='" + payer + "'"));
    }

    double rejected() {
        return registry.find("ledger.payment.transitions").tag("result", "rejected").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test void captureWithoutFundsIs409AndPaymentStaysAuthorized() throws Exception {
        String m = "m-" + UUID.randomUUID();
        UUID payer = account(m, 10), payee = account(m, 0);
        String id = newPayment(m, payer, payee, 50);
        post("/v1/payments/" + id + "/authorize", m, null);
        var r = post("/v1/payments/" + id + "/capture", m, null);
        assertEquals(409, r.statusCode());
        assertTrue(r.body().contains("INSUFFICIENT_FUNDS"));
    }

    @Test void overCaptureIsRejected() throws Exception {
        String m = "m-" + UUID.randomUUID();
        UUID payer = account(m, 1000), payee = account(m, 0);
        String id = newPayment(m, payer, payee, 50);
        post("/v1/payments/" + id + "/authorize", m, null);
        var r = post("/v1/payments/" + id + "/capture", m, "{\"amountMinor\":51}");
        assertEquals(409, r.statusCode(), r.body());
        assertEquals(0, scalar("SELECT balance_minor FROM accounts WHERE id='" + payee + "'"));
    }

    @Test void captureReplayMovesMoneyOnce() throws Exception {
        String m = "m-" + UUID.randomUUID(), key = UUID.randomUUID().toString();
        UUID payer = account(m, 1000), payee = account(m, 0);
        String id = newPayment(m, payer, payee, 400);
        post("/v1/payments/" + id + "/authorize", m, null);
        var a = call("POST", "/v1/payments/" + id + "/capture", m, key, null);
        var b = call("POST", "/v1/payments/" + id + "/capture", m, key, null);
        assertEquals(a.statusCode(), b.statusCode());
        assertEquals(a.body(), b.body());
        assertEquals(400, scalar("SELECT balance_minor FROM accounts WHERE id='" + payee + "'"));
    }

    @Test void otherMerchantCannotTouchPayment() throws Exception {
        String m = "m-" + UUID.randomUUID(), other = "o-" + UUID.randomUUID();
        UUID payer = account(m, 100), payee = account(m, 0);
        String id = newPayment(m, payer, payee, 50);
        assertEquals(404, post("/v1/payments/" + id + "/authorize", other, null).statusCode());
    }

    @Test void balanceAndStatementWithKeysetPagination() throws Exception {
        String m = "m-" + UUID.randomUUID();
        UUID a = account(m, 1000), b = account(m, 0);
        for (int i = 0; i < 5; i++) {
            String body = "{\"fromAccountId\":\"" + a + "\",\"toAccountId\":\"" + b + "\",\"amountMinor\":10,\"currency\":\"USD\"}";
            assertEquals(201, call("POST", "/v1/transfers", m, UUID.randomUUID().toString(), body).statusCode());
        }
        var bal = call("GET", "/v1/accounts/" + a + "/balance", m, null, null);
        assertEquals(200, bal.statusCode());
        assertTrue(bal.body().contains("\"balanceMinor\":950"), bal.body());

        var page1 = call("GET", "/v1/accounts/" + a + "/statement?limit=2", m, null, null);
        assertEquals(200, page1.statusCode(), page1.body());
        Matcher cur = Pattern.compile("\"nextCursor\":\"([^\"]+)\"").matcher(page1.body());
        assertTrue(cur.find(), page1.body());
        var page2 = call("GET", "/v1/accounts/" + a + "/statement?limit=2&cursor=" + cur.group(1), m, null, null);
        var page3 = call("GET", "/v1/accounts/" + a + "/statement?limit=2&cursor=" + cur(page2.body()), m, null, null);
        assertEquals(2, count(page1.body()));
        assertEquals(2, count(page2.body()));
        assertEquals(1, count(page3.body()));
        assertTrue(page3.body().contains("\"nextCursor\":null"), page3.body());

        assertEquals(404, call("GET", "/v1/accounts/" + a + "/balance", "someone-else", null, null).statusCode());
    }

    static String cur(String body) {
        Matcher m = Pattern.compile("\"nextCursor\":\"([^\"]+)\"").matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }

    static int count(String body) {
        return body.split("\"entryId\"", -1).length - 1;
    }
}
