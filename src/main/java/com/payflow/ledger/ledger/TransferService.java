package com.payflow.ledger.ledger;

import com.payflow.ledger.idempotency.IdempotencyGuard;
import com.payflow.ledger.telemetry.LedgerMetrics;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** One Postgres transaction per request: idempotency row, entries, balances, hash heads, outbox, stored response. */
@Service
public class TransferService {
    private final TransferStrategy strategy;
    private final TransactionTemplate tx;
    private final IdempotencyGuard guard;
    private final OutboxWriter outbox;
    private final LedgerMetrics metrics;
    private final ObjectMapper json;

    public TransferService(List<TransferStrategy> strategies, PlatformTransactionManager tm, IdempotencyGuard guard,
                           OutboxWriter outbox, LedgerMetrics metrics, ObjectMapper json,
                           @Value("${ledger.strategy:PESSIMISTIC}") String strategyName) {
        this.strategy = strategies.stream().filter(s -> s.name().equalsIgnoreCase(strategyName)).findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown ledger.strategy " + strategyName));
        this.tx = new TransactionTemplate(tm);
        this.tx.setIsolationLevel(strategy.isolation());
        this.guard = guard;
        this.outbox = outbox;
        this.metrics = metrics;
        this.json = json;
    }

    public ApiResult transfer(TransferCommand cmd, String idempotencyKey) {
        if (cmd.fromAccountId().equals(cmd.toAccountId())) {
            throw new LedgerException(400, "SAME_ACCOUNT", "source and destination must differ");
        }
        CountingJdbc.reset();
        long t0 = System.nanoTime();
        Span span = Span.current();
        span.setAttribute("ledger.strategy", strategy.name());
        span.setAttribute("ledger.attempt", 1L);
        String outcome = "error";
        try {
            byte[] hash = IdempotencyGuard.sha256(canonical(cmd));
            ApiResult result = tx.execute(status -> execute(cmd, idempotencyKey, hash, span));
            outcome = result.status() == 201 ? "ok" : result.status() == 409 ? "insufficient_funds" : "error";
            return result;
        } catch (LedgerException e) {
            outcome = e.httpStatus() == 422 ? "conflict" : "error";
            throw e;
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
            throw e;
        } finally {
            metrics.roundtrips("transfer", CountingJdbc.count());
            if (lastWasFirst.get()) {
                metrics.transferDuration(strategy.name(), outcome, Duration.ofNanos(System.nanoTime() - t0));
            }
            lastWasFirst.remove();
            com.payflow.ledger.idempotency.IdempotencyContext.clear();
        }
    }

    private final ThreadLocal<Boolean> lastWasFirst = ThreadLocal.withInitial(() -> false);

    private ApiResult execute(TransferCommand cmd, String key, byte[] requestHash, Span span) {
        lastWasFirst.set(false);
        Optional<IdempotencyGuard.Replay> replay = guard.claim(cmd.merchantId(), key, requestHash);
        if (replay.isPresent()) {
            return new ApiResult(replay.get().status(), replay.get().body());
        }
        lastWasFirst.set(true);
        com.payflow.ledger.idempotency.IdempotencyContext.set(cmd.merchantId(), key);

        PostResult post = strategy.post(cmd);
        ApiResult result;
        if (post instanceof PostResult.Posted p) {
            span.setAttribute("ledger.txn_id", p.txnId().toString());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("transferId", p.txnId().toString());
            body.put("status", "POSTED");
            body.put("fromAccountId", cmd.fromAccountId().toString());
            body.put("toAccountId", cmd.toAccountId().toString());
            body.put("amountMinor", cmd.amountMinor());
            body.put("currency", cmd.currency());
            String bodyJson = json.writeValueAsString(body);
            outbox.write("transfer", p.txnId().toString(), "TransferPosted", 1, cmd.merchantId(),
                    cmd.amountMinor(), cmd.currency(), bodyJson);
            result = new ApiResult(201, bodyJson);
        } else {
            PostResult.Rejected r = (PostResult.Rejected) post;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "about:blank");
            body.put("title", r.code());
            body.put("status", r.httpStatus());
            body.put("detail", r.message());
            body.put("code", r.code());
            result = new ApiResult(r.httpStatus(), json.writeValueAsString(body));
        }
        guard.store(cmd.merchantId(), key, result.status(), result.body());
        return result;
    }

    /** Canonical JSON (sorted keys) of the request body, bound to the endpoint. */
    private String canonical(TransferCommand c) {
        Map<String, Object> m = new TreeMap<>();
        m.put("amountMinor", c.amountMinor());
        m.put("currency", c.currency());
        m.put("fromAccountId", c.fromAccountId().toString());
        m.put("toAccountId", c.toAccountId().toString());
        return "POST /v1/transfers " + json.writeValueAsString(m);
    }
}
