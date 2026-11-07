package com.payflow.ledger.ledger;

import com.payflow.ledger.idempotency.IdempotencyGuard;
import com.payflow.ledger.telemetry.LedgerMetrics;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Runs work inside the single money transaction guarded by an idempotency key. */
@Component
public class IdempotentTx {
    private final TransactionTemplate tx;
    private final IdempotencyGuard guard;
    private final LedgerMetrics metrics;
    private final ObjectMapper json;

    public IdempotentTx(PlatformTransactionManager tm, IdempotencyGuard guard, LedgerMetrics metrics, ObjectMapper json) {
        this.tx = new TransactionTemplate(tm);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.guard = guard;
        this.metrics = metrics;
        this.json = json;
    }

    public ApiResult run(String operation, String merchantId, String key, String canonicalRequest, Supplier<ApiResult> work) {
        CountingJdbc.reset();
        try {
            byte[] hash = IdempotencyGuard.sha256(canonicalRequest);
            return tx.execute(status -> {
                Optional<IdempotencyGuard.Replay> replay = guard.claim(merchantId, key, hash);
                if (replay.isPresent()) return new ApiResult(replay.get().status(), replay.get().body());
                com.payflow.ledger.idempotency.IdempotencyContext.set(merchantId, key);
                ApiResult r = work.get();
                guard.store(merchantId, key, r.status(), r.body());
                return r;
            });
        } finally {
            com.payflow.ledger.idempotency.IdempotencyContext.clear();
            metrics.roundtrips(operation, CountingJdbc.count());
        }
    }

    public ApiResult ok(int status, Map<String, Object> body) {
        return new ApiResult(status, json.writeValueAsString(body));
    }

    public ApiResult problem(int status, String code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", code);
        body.put("status", status);
        body.put("detail", detail);
        body.put("code", code);
        return new ApiResult(status, json.writeValueAsString(body));
    }

    public String toJson(Object o) {
        return json.writeValueAsString(o);
    }
}
