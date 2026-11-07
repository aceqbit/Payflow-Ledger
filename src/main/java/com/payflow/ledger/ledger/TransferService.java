package com.payflow.ledger.ledger;

import com.payflow.ledger.idempotency.IdempotencyContext;
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

/**
 * One Postgres transaction per attempt: idempotency row, entries, balances, hash heads, outbox, stored response.
 * Conflicts roll the whole attempt back (including the idempotency row) and are retried by the shared RetryPolicy.
 */
@Service
public class TransferService {
    private final TransferStrategy strategy;
    private final TransactionTemplate tx;
    private final IdempotencyGuard guard;
    private final OutboxWriter outbox;
    private final LedgerMetrics metrics;
    private final ObjectMapper json;
    private final RetryPolicy retry;
    private final CountingJdbc jdbc;
    private final boolean sqlFunction;
    private final boolean outboxDelete;

    public TransferService(List<TransferStrategy> strategies, PlatformTransactionManager tm, IdempotencyGuard guard,
                           OutboxWriter outbox, LedgerMetrics metrics, ObjectMapper json, RetryPolicy retry, CountingJdbc jdbc,
                           @Value("${ledger.strategy:PESSIMISTIC}") String strategyName,
                           @Value("${ledger.transfer.sql-function:false}") boolean sqlFunction,
                           @Value("${ledger.outbox.mode:retain}") String outboxMode) {
        this.strategy = strategies.stream().filter(s -> s.name().equalsIgnoreCase(strategyName)).findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown ledger.strategy " + strategyName));
        this.tx = new TransactionTemplate(tm);
        this.tx.setIsolationLevel(strategy.isolation());
        this.guard = guard;
        this.outbox = outbox;
        this.metrics = metrics;
        this.json = json;
        this.retry = retry;
        this.jdbc = jdbc;
        this.sqlFunction = sqlFunction && strategy.name().equals(PessimisticTransferStrategy.NAME);
        this.outboxDelete = "delete".equalsIgnoreCase(outboxMode);
    }

    public String strategyName() {
        return strategy.name();
    }

    public ApiResult transfer(TransferCommand cmd, String idempotencyKey) {
        if (cmd.fromAccountId().equals(cmd.toAccountId())) {
            throw new LedgerException(400, "SAME_ACCOUNT", "source and destination must differ");
        }
        CountingJdbc.reset();
        long t0 = System.nanoTime();
        Span span = Span.current();
        span.setAttribute("ledger.strategy", strategy.name());
        String outcome = "error";
        boolean first = false;
        int attempt = 0;
        boolean useFn = sqlFunction;
        try {
            byte[] hash = IdempotencyGuard.sha256(canonical(cmd));
            while (true) {
                attempt++;
                span.setAttribute("ledger.attempt", (long) attempt);
                try {
                    final boolean fn = useFn;
                    ApiResult result = tx.execute(status -> fn ? executeFunction(cmd, idempotencyKey, hash) : execute(cmd, idempotencyKey, hash, span));
                    first = lastWasFirst.get();
                    outcome = result.status() == 201 ? "ok" : result.status() == 409 ? "insufficient_funds" : "error";
                    metrics.attempts(strategy.name(), attempt);
                    return result;
                } catch (RuntimeException e) {
                    String sqlState = RetryPolicy.sqlStateOf(e);
                    if (useFn && "PF001".equals(sqlState)) { // hot account: take the Java path, not a retry
                        useFn = false;
                        attempt--;
                        continue;
                    }
                    if ("PF404".equals(sqlState)) throw new LedgerException(404, "ACCOUNT_NOT_FOUND", "account not found");
                    if ("PF400".equals(sqlState)) throw new LedgerException(400, "CURRENCY_MISMATCH", "accounts and transfer must share one currency");
                    String reason = retry.reasonOf(e);
                    if (reason == null) throw e;
                    metrics.retry(strategy.name(), reason);
                    if (attempt >= RetryPolicy.MAX_ATTEMPTS) {
                        first = true;
                        metrics.attempts(strategy.name(), attempt);
                        throw new LedgerException(503, "CONFLICT_RETRIES_EXHAUSTED",
                                "gave up after " + attempt + " attempts (" + reason + "); retry with the same Idempotency-Key");
                    }
                    retry.sleep(attempt);
                }
            }
        } catch (LedgerException e) {
            outcome = e.httpStatus() == 422 || e.httpStatus() == 503 ? "conflict" : "error";
            throw e;
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
            throw e;
        } finally {
            metrics.roundtrips("transfer", CountingJdbc.count());
            if (first || lastWasFirst.get()) {
                metrics.transferDuration(strategy.name(), outcome, Duration.ofNanos(System.nanoTime() - t0));
            }
            lastWasFirst.remove();
            IdempotencyContext.clear();
        }
    }

    /** T4: the whole transfer is one SQL function call (one round trip). */
    private ApiResult executeFunction(TransferCommand cmd, String key, byte[] requestHash) {
        lastWasFirst.set(false);
        var row = jdbc.sql("SELECT outcome, status, body FROM post_transfer(:m, :k, :h, :f, :t, :a, :c, :tp, :del)")
                .param("m", cmd.merchantId()).param("k", key).param("h", requestHash)
                .param("f", cmd.fromAccountId()).param("t", cmd.toAccountId()).param("a", cmd.amountMinor())
                .param("c", cmd.currency()).param("tp", OutboxWriter.traceparent()).param("del", outboxDelete)
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getInt(2), rs.getString(3)}).single();
        String outcome = (String) row[0];
        metrics.idempotency(outcome);
        if (outcome.equals("mismatch")) {
            throw new LedgerException(422, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was used with a different request body");
        }
        if (outcome.equals("new")) {
            lastWasFirst.set(true);
            if ((int) row[1] == 201) metrics.outboxWrite("TransferPosted");
        }
        return new ApiResult((int) row[1], (String) row[2]);
    }

    private final ThreadLocal<Boolean> lastWasFirst = ThreadLocal.withInitial(() -> false);

    private ApiResult execute(TransferCommand cmd, String key, byte[] requestHash, Span span) {
        lastWasFirst.set(false);
        Optional<IdempotencyGuard.Replay> replay = guard.claim(cmd.merchantId(), key, requestHash);
        if (replay.isPresent()) {
            return new ApiResult(replay.get().status(), replay.get().body());
        }
        lastWasFirst.set(true);
        IdempotencyContext.set(cmd.merchantId(), key);

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
