package com.payflow.ledger.payment;

import com.payflow.ledger.ledger.ApiResult;
import com.payflow.ledger.ledger.CountingJdbc;
import com.payflow.ledger.ledger.IdempotentTx;
import com.payflow.ledger.ledger.LedgerException;
import com.payflow.ledger.ledger.OutboxWriter;
import com.payflow.ledger.ledger.Posting;
import com.payflow.ledger.telemetry.LedgerMetrics;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Every payment move goes through the same idempotency + posting + outbox path as transfers. */
@Service
public class PaymentService {
    record Pay(UUID id, UUID payer, UUID payee, String currency, PaymentState state, long amount,
               long authorized, long captured, long refunded, long version) {}

    private final IdempotentTx idem;
    private final CountingJdbc jdbc;
    private final Posting posting;
    private final OutboxWriter outbox;
    private final LedgerMetrics metrics;

    public PaymentService(IdempotentTx idem, CountingJdbc jdbc, Posting posting, OutboxWriter outbox, LedgerMetrics metrics) {
        this.idem = idem;
        this.jdbc = jdbc;
        this.posting = posting;
        this.outbox = outbox;
        this.metrics = metrics;
    }

    public ApiResult create(String merchant, String key, UUID payer, UUID payee, long amount, String currency) {
        if (payer.equals(payee)) throw new LedgerException(400, "SAME_ACCOUNT", "payer and payee must differ");
        String canon = "POST /v1/payments " + payer + "|" + payee + "|" + amount + "|" + currency;
        return idem.run("payment_create", merchant, key, canon, () -> {
            jdbc.sql("SET LOCAL lock_timeout = '2s'").update();
            List<Posting.Acct> locked = posting.lockPair(merchant, payer, payee);
            Posting.Acct a = Posting.pick(locked, payer, "payer"), b = Posting.pick(locked, payee, "payee");
            if (!a.currency().trim().equals(currency) || !b.currency().trim().equals(currency)) {
                throw new LedgerException(400, "CURRENCY_MISMATCH", "accounts and payment must share one currency");
            }
            UUID id = jdbc.sql("""
                    INSERT INTO payments (merchant_id, currency, state, payer_account_id, payee_account_id, amount_minor, version)
                    VALUES (:m, :c, 'CREATED', :p, :q, :amt, 1) RETURNING id""")
                    .param("m", merchant).param("c", currency).param("p", payer).param("q", payee).param("amt", amount)
                    .query(UUID.class).single();
            Pay p = new Pay(id, payer, payee, currency, PaymentState.CREATED, amount, 0, 0, 0, 1);
            outbox.write("payment", id.toString(), "PaymentCreated", 1, merchant, amount, currency, json(p));
            return idem.ok(201, view(p));
        });
    }

    public ApiResult authorize(String merchant, String key, UUID id) {
        return idem.run("payment_authorize", merchant, key, "POST /v1/payments/" + id + "/authorize", () -> {
            Pay p = load(merchant, id);
            var rejected = guardTransition(p, PaymentState.AUTHORIZED);
            if (rejected != null) return rejected;
            Pay n = save(p, PaymentState.AUTHORIZED, p.amount(), p.captured(), p.refunded());
            outbox.write("payment", id.toString(), "PaymentAuthorized", n.version(), merchant, n.authorized(), n.currency(), json(n));
            return idem.ok(200, view(n));
        });
    }

    public ApiResult capture(String merchant, String key, UUID id, Long requested) {
        return idem.run("capture", merchant, key, "POST /v1/payments/" + id + "/capture " + requested, () -> {
            jdbc.sql("SET LOCAL lock_timeout = '2s'").update();
            Pay p = load(merchant, id);
            if (!p.state().canMoveTo(PaymentState.CAPTURED) && !p.state().canMoveTo(PaymentState.PARTIALLY_CAPTURED)) {
                return guardTransition(p, PaymentState.CAPTURED);
            }
            long amt = requested == null ? p.authorized() - p.captured() : requested;
            if (amt <= 0) return idem.problem(409, "NOTHING_TO_CAPTURE", "no capturable amount");
            PaymentState to = p.captured() + amt == p.authorized() ? PaymentState.CAPTURED : PaymentState.PARTIALLY_CAPTURED;
            var rejected = guardTransition(p, to);
            if (rejected != null) return rejected;
            if (p.captured() + amt > p.authorized()) return idem.problem(409, "AMOUNT_EXCEEDS_AUTHORIZED", "capture exceeds authorized amount");
            var moved = move(merchant, p.payer(), p.payee(), amt, p.currency());
            if (moved != null) return moved;
            Pay n = save(p, to, p.authorized(), p.captured() + amt, p.refunded());
            outbox.write("payment", id.toString(), "PaymentCaptured", n.version(), merchant, amt, n.currency(), json(n));
            return idem.ok(200, view(n));
        });
    }

    public ApiResult refund(String merchant, String key, UUID id, Long requested) {
        return idem.run("refund", merchant, key, "POST /v1/payments/" + id + "/refund " + requested, () -> {
            jdbc.sql("SET LOCAL lock_timeout = '2s'").update();
            Pay p = load(merchant, id);
            if (!p.state().canMoveTo(PaymentState.REFUNDED) && !p.state().canMoveTo(PaymentState.PARTIALLY_REFUNDED)) {
                return guardTransition(p, PaymentState.REFUNDED);
            }
            long amt = requested == null ? p.captured() - p.refunded() : requested;
            if (amt <= 0) return idem.problem(409, "NOTHING_TO_REFUND", "no refundable amount");
            PaymentState to = p.refunded() + amt == p.captured() ? PaymentState.REFUNDED : PaymentState.PARTIALLY_REFUNDED;
            var rejected = guardTransition(p, to);
            if (rejected != null) return rejected;
            if (p.refunded() + amt > p.captured()) return idem.problem(409, "AMOUNT_EXCEEDS_CAPTURED", "refund exceeds captured amount");
            var moved = move(merchant, p.payee(), p.payer(), amt, p.currency());
            if (moved != null) return moved;
            Pay n = save(p, to, p.authorized(), p.captured(), p.refunded() + amt);
            outbox.write("payment", id.toString(), "PaymentRefunded", n.version(), merchant, amt, n.currency(), json(n));
            return idem.ok(200, view(n));
        });
    }

    public ApiResult voidPayment(String merchant, String key, UUID id) {
        return idem.run("payment_void", merchant, key, "POST /v1/payments/" + id + "/void", () -> {
            Pay p = load(merchant, id);
            var rejected = guardTransition(p, PaymentState.VOIDED);
            if (rejected != null) return rejected;
            Pay n = save(p, PaymentState.VOIDED, p.authorized(), p.captured(), p.refunded());
            outbox.write("payment", id.toString(), "PaymentVoided", n.version(), merchant, n.authorized(), n.currency(), json(n));
            return idem.ok(200, view(n));
        });
    }

    // ---- helpers ----
    private Pay load(String merchant, UUID id) {
        return jdbc.sql("""
                SELECT id, payer_account_id, payee_account_id, currency, state, amount_minor, authorized, captured, refunded, version
                FROM payments WHERE id = :id AND merchant_id = :m FOR UPDATE""")
                .param("id", id).param("m", merchant)
                .query((rs, n) -> new Pay(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4).trim(), PaymentState.valueOf(rs.getString(5)), rs.getLong(6),
                        rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10)))
                .optional().orElseThrow(() -> new LedgerException(404, "PAYMENT_NOT_FOUND", "payment not found"));
    }

    /** Returns a stored 409 when the transition is illegal, null when allowed. Counts accepted and rejected. */
    private ApiResult guardTransition(Pay p, PaymentState to) {
        if (!p.state().canMoveTo(to)) {
            metrics.transition(p.state().name(), to.name(), "rejected");
            return idem.problem(409, "ILLEGAL_TRANSITION", "cannot move payment from " + p.state() + " to " + to);
        }
        metrics.transition(p.state().name(), to.name(), "accepted");
        return null;
    }

    private Pay save(Pay p, PaymentState to, long authorized, long captured, long refunded) {
        long v = jdbc.sql("""
                UPDATE payments SET state = :s, authorized = :a, captured = :c, refunded = :r, version = version + 1
                WHERE id = :id RETURNING version""")
                .param("s", to.name()).param("a", authorized).param("c", captured).param("r", refunded).param("id", p.id())
                .query(Long.class).single();
        return new Pay(p.id(), p.payer(), p.payee(), p.currency(), to, p.amount(), authorized, captured, refunded, v);
    }

    /** Posts the ledger move; returns a stored 409 when funds are short, null on success. */
    private ApiResult move(String merchant, UUID from, UUID to, long amt, String currency) {
        List<Posting.Acct> locked = posting.lockPair(merchant, from, to);
        Posting.Acct f = Posting.pick(locked, from, "source"), t = Posting.pick(locked, to, "destination");
        if (!f.system() && f.balanceMinor() < amt) return idem.problem(409, "INSUFFICIENT_FUNDS", "insufficient funds");
        posting.transfer(f, t, amt);
        return null;
    }

    private Map<String, Object> view(Pay p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentId", p.id().toString());
        m.put("state", p.state().name());
        m.put("payerAccountId", p.payer().toString());
        m.put("payeeAccountId", p.payee().toString());
        m.put("currency", p.currency());
        m.put("amountMinor", p.amount());
        m.put("authorizedMinor", p.authorized());
        m.put("capturedMinor", p.captured());
        m.put("refundedMinor", p.refunded());
        m.put("version", p.version());
        return m;
    }

    private String json(Pay p) {
        return idem.toJson(view(p));
    }
}
