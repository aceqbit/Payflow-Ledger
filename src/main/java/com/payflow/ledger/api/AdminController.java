package com.payflow.ledger.api;

import com.payflow.ledger.telemetry.LedgerMetrics;
import com.payflow.ledger.verify.VerifyScheduler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Requires the "admin" scope (authority SCOPE_admin). */
@RestController
public class AdminController {
    private final VerifyScheduler verify;
    private final LedgerMetrics metrics;

    public AdminController(VerifyScheduler verify, LedgerMetrics metrics) {
        this.verify = verify;
        this.metrics = metrics;
    }

    @PostMapping("/admin/verify")
    Map<String, Object> verify() {
        return verify.runOnce();
    }

    /** Tamper runs report how many mutations they injected per kind: the denominator of the detection rate. */
    @PostMapping("/admin/tamper-injected")
    Map<String, Long> tamperInjected(@RequestBody Map<String, Long> injected) {
        for (String kind : LedgerMetrics.CHAIN_KINDS) {
            metrics.setTamperInjected(kind, injected.getOrDefault(kind, 0L));
        }
        return injected;
    }
}
