package com.payflow.ledger.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Runs both verifiers every 60 s (configurable) and exports their gauges. */
@Component
public class VerifyScheduler {
    private static final Logger log = LoggerFactory.getLogger(VerifyScheduler.class);

    private final InvariantChecker invariants;
    private final ChainVerifier chain;
    private final boolean enabled;

    public VerifyScheduler(InvariantChecker invariants, ChainVerifier chain, @Value("${ledger.verify.enabled:true}") boolean enabled) {
        this.invariants = invariants;
        this.chain = chain;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${ledger.verify.interval-ms:60000}", initialDelayString = "${ledger.verify.initial-delay-ms:5000}")
    void scheduled() {
        if (enabled) runOnce();
    }

    public Map<String, Object> runOnce() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invariants", invariants.run());
        var c = chain.verifyAll();
        out.put("chainEntriesVerified", c.entries());
        out.put("chainBreaks", c.breaks());
        log.info("verify run: {}", out);
        return out;
    }
}
