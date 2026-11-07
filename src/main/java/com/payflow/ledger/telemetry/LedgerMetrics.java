package com.payflow.ledger.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The only class that creates custom meters. OTel names use dots; Prometheus shows
 * e.g. ledger_transfer_duration_seconds. Never label by any id.
 */
@Component
public class LedgerMetrics {
    public static final Duration[] SLO_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500)};
    public static final List<String> INVARIANT_CHECKS = List.of("global_sum", "negative_balance", "balance_mismatch", "duplicate_charge");
    public static final List<String> CHAIN_KINDS = List.of("edit", "delete", "reorder");

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> invariantViolations = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> chainBreaks = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> tamperInjected = new ConcurrentHashMap<>();
    private final AtomicLong lastRunEpochSeconds = new AtomicLong(0);
    private final Counter chainEntriesVerified;
    private final Timer chainVerify;
    private final Timer idempotencyGuard;

    public LedgerMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String c : INVARIANT_CHECKS) {
            AtomicLong v = new AtomicLong(0);
            invariantViolations.put(c, v);
            Gauge.builder("ledger.invariant.violations", v, AtomicLong::doubleValue).tag("check", c).register(registry);
        }
        for (String k : CHAIN_KINDS) {
            AtomicLong b = new AtomicLong(0), t = new AtomicLong(0);
            chainBreaks.put(k, b);
            tamperInjected.put(k, t);
            Gauge.builder("ledger.chain.breaks", b, AtomicLong::doubleValue).tag("kind", k).register(registry);
            Gauge.builder("ledger.tamper.injected", t, AtomicLong::doubleValue).tag("kind", k).register(registry);
        }
        Gauge.builder("ledger.invariant.last.run.timestamp", lastRunEpochSeconds, AtomicLong::doubleValue)
                .baseUnit("seconds").register(registry);
        chainEntriesVerified = Counter.builder("ledger.chain.entries.verified").register(registry);
        chainVerify = Timer.builder("ledger.chain.verify").serviceLevelObjectives(
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(300))
                .register(registry);
        idempotencyGuard = slo("ledger.idempotency.guard", Tags.empty());
    }

    private Timer slo(String name, Tags tags) {
        return Timer.builder(name).tags(tags).serviceLevelObjectives(SLO_BUCKETS).register(registry);
    }

    // ---- request and money path ----
    public void transferDuration(String strategy, String outcome, Duration d) {
        slo("ledger.transfer.duration", Tags.of("strategy", strategy, "outcome", outcome)).record(d);
        Counter.builder("ledger.transfers").tags("strategy", strategy, "outcome", outcome).register(registry).increment();
    }

    public void retry(String strategy, String reason) {
        Counter.builder("ledger.transfer.retries").tags("strategy", strategy, "reason", reason).register(registry).increment();
    }

    public void attempts(String strategy, int attempts) {
        DistributionSummary.builder("ledger.transfer.attempts").tag("strategy", strategy).register(registry).record(attempts);
    }

    public void lockWait(String strategy, Duration d) {
        slo("ledger.lock.wait", Tags.of("strategy", strategy)).record(d);
    }

    public void roundtrips(String operation, int n) {
        DistributionSummary.builder("ledger.db.roundtrips").tag("operation", operation).register(registry).record(n);
    }

    public void idempotency(String result) {
        Counter.builder("ledger.idempotency").tag("result", result).register(registry).increment();
    }

    public void idempotencyGuard(Duration d) {
        idempotencyGuard.record(d);
    }

    public void transition(String from, String to, String result) {
        Counter.builder("ledger.payment.transitions").tags("from", from, "to", to, "result", result).register(registry).increment();
    }

    public void outboxWrite(String type) {
        Counter.builder("ledger.outbox.writes").tag("type", type).register(registry).increment();
    }

    public void hotBucketLock(int buckets, Duration d) {
        slo("ledger.hot.bucket.lock", Tags.of("buckets", Integer.toString(buckets))).record(d);
    }

    // ---- correctness (verifiers) ----
    public void setInvariantViolations(String check, long n) {
        invariantViolations.get(check).set(n);
    }

    public void markInvariantRun(long epochSeconds) {
        lastRunEpochSeconds.set(epochSeconds);
    }

    public void setChainBreaks(String kind, long n) {
        chainBreaks.get(kind).set(n);
    }

    public void setTamperInjected(String kind, long n) {
        tamperInjected.get(kind).set(n);
    }

    public void chainEntriesVerified(long n) {
        chainEntriesVerified.increment(n);
    }

    public void chainVerify(Duration d) {
        chainVerify.record(d);
    }
}
