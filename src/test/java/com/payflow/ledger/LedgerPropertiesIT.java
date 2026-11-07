package com.payflow.ledger;

import com.payflow.ledger.ledger.ApiResult;
import com.payflow.ledger.ledger.TransferCommand;
import com.payflow.ledger.ledger.TransferService;
import com.payflow.ledger.payment.PaymentService;
import com.payflow.ledger.payment.PaymentState;
import com.payflow.ledger.payment.PaymentStateTest;
import com.payflow.ledger.support.LedgerFixture;
import com.payflow.ledger.support.SpringDbTest;
import com.payflow.ledger.support.TestDb;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Random sequences of authorize / partial capture / refund / void / transfer. After every step: money is
 * conserved, balances are non-negative, balance == sum(entries), entries sum to 0, a 409 changes nothing
 * and a 200 only ever follows a legal transition (checked against an independently written table).
 * Tries come from -Djqwik.tries.default (10k on push, 10M nightly).
 */
class LedgerPropertiesIT {
    static TestDb db;
    static final java.util.Map<String, ConfigurableApplicationContext> CTX = new java.util.LinkedHashMap<>();
    static final java.util.Map<String, PaymentService> PAYMENTS = new java.util.HashMap<>();
    static final java.util.Map<String, TransferService> TRANSFERS = new java.util.HashMap<>();
    static Connection conn;

    @BeforeContainer static void up() throws Exception {
        db = TestDb.create();
        for (String strategy : List.of("PESSIMISTIC", "OPTIMISTIC", "SERIALIZABLE")) {
            var c = new SpringApplicationBuilder(PayflowLedgerApplication.class).run("--server.port=0",
                    "--spring.datasource.url=" + db.url, "--spring.datasource.username=" + TestDb.USER,
                    "--spring.datasource.password=" + TestDb.PASSWORD, "--management.otlp.metrics.export.enabled=false",
                    "--ledger.verify.enabled=false", "--spring.main.banner-mode=off", "--ledger.strategy=" + strategy);
            CTX.put(strategy, c);
            PAYMENTS.put(strategy, c.getBean(PaymentService.class));
            TRANSFERS.put(strategy, c.getBean(TransferService.class));
        }
        conn = db.connect();
    }

    @AfterContainer static void down() {
        CTX.values().forEach(ConfigurableApplicationContext::close);
        try { conn.close(); } catch (Exception ignored) { }
        db.drop();
    }

    enum Op { AUTHORIZE, CAPTURE_FULL, CAPTURE_PART, REFUND_FULL, REFUND_PART, VOID, TRANSFER }

    @Property
    void invariantsHoldAfterEveryStep(@ForAll("strategies") String strategy, @ForAll("ops") List<Op> ops, @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 500) int amount,
                                      @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 500) int part) throws Exception {
        PaymentService payments = PAYMENTS.get(strategy);
        TransferService transfers = TRANSFERS.get(strategy);
        String m = "prop-" + UUID.randomUUID();
        UUID payer, payee;
        {
            Connection c = conn;
            payer = LedgerFixture.account(c, m, false);
            payee = LedgerFixture.account(c, m, false);
            // accounts are scoped by merchant, and the funding source must be of the same merchant
            UUID msys = LedgerFixture.account(c, m, true);
            LedgerFixture.transfer(c, msys, payer, 400, "fund-" + UUID.randomUUID());
            LedgerFixture.transfer(c, msys, payee, 100, "fund-" + UUID.randomUUID());
        }
        long total = 500;
        ApiResult created = payments.create(m, UUID.randomUUID().toString(), payer, payee, amount, "USD");
        assertEquals(201, created.status());
        UUID id = UUID.fromString(field(created.body(), "paymentId"));

        for (Op op : ops) {
            PaymentState before = state(id);
            String key = UUID.randomUUID().toString();
            ApiResult r = switch (op) {
                case AUTHORIZE -> payments.authorize(m, key, id);
                case CAPTURE_FULL -> payments.capture(m, key, id, null);
                case CAPTURE_PART -> payments.capture(m, key, id, (long) part);
                case REFUND_FULL -> payments.refund(m, key, id, null);
                case REFUND_PART -> payments.refund(m, key, id, (long) part);
                case VOID -> payments.voidPayment(m, key, id);
                case TRANSFER -> transfers.transfer(new TransferCommand(m, payer, payee, part, "USD"), key);
            };
            PaymentState after = state(id);
            if (op == Op.TRANSFER) {
                assertEquals(before, after, "a plain transfer never touches payment state");
            } else if (r.status() == 200) {
                assertTrue(PaymentStateTest.EXPECTED.get(before).contains(after), op + ": " + before + " -> " + after + " is not a legal move");
            } else {
                assertEquals(409, r.status(), r.body());
                assertEquals(before, after, op + " was rejected, so the state must not change");
            }
            checkMoney(payer, payee, id, total);
        }
    }

    @Provide Arbitrary<String> strategies() {
        return Arbitraries.of("PESSIMISTIC", "OPTIMISTIC", "SERIALIZABLE");
    }

    @Provide Arbitrary<List<Op>> ops() {
        return Arbitraries.of(Op.values()).list().ofMinSize(1).ofMaxSize(8);
    }

    static String field(String json, String name) {
        Matcher mt = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(mt.find(), json);
        return mt.group(1);
    }

    static PaymentState state(UUID id) throws Exception {
        try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery("SELECT state FROM payments WHERE id='" + id + "'")) {
            r.next(); return PaymentState.valueOf(r.getString(1));
        }
    }

    static void checkMoney(UUID payer, UUID payee, UUID payment, long total) throws Exception {
        try (Statement s = conn.createStatement()) {
            ResultSet r = s.executeQuery("""
                    SELECT sum(balance_minor), min(balance_minor),
                           (SELECT coalesce(sum(amount_minor),0) FROM ledger_entries WHERE account_id IN ('%1$s','%2$s')),
                           (SELECT count(*) FROM accounts a WHERE a.id IN ('%1$s','%2$s') AND a.balance_minor <>
                               (SELECT coalesce(sum(amount_minor),0) FROM ledger_entries e WHERE e.account_id = a.id)),
                           (SELECT count(*) FROM (SELECT txn_id FROM ledger_entries WHERE account_id IN ('%1$s','%2$s')
                                                  GROUP BY txn_id HAVING sum(amount_minor) <> 0) q)
                    FROM accounts WHERE id IN ('%1$s','%2$s')""".formatted(payer, payee));
            r.next();
            assertEquals(total, r.getLong(1), "money is conserved between payer and payee");
            assertTrue(r.getLong(2) >= 0, "no negative balance");
            assertEquals(total, r.getLong(3) - 0, "entries of the two accounts sum to the funded total");
            assertEquals(0, r.getLong(4), "stored balance equals sum of entries");
            r = s.executeQuery("SELECT authorized >= captured AND captured >= refunded FROM payments WHERE id='" + payment + "'");
            r.next();
            assertTrue(r.getBoolean(1));
        }
    }
}
