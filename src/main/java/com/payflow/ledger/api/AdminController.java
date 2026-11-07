package com.payflow.ledger.api;

import com.payflow.ledger.verify.VerifyScheduler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Requires the "admin" scope (authority SCOPE_admin). */
@RestController
public class AdminController {
    private final VerifyScheduler verify;

    public AdminController(VerifyScheduler verify) {
        this.verify = verify;
    }

    @PostMapping("/admin/verify")
    Map<String, Object> verify() {
        return verify.runOnce();
    }
}
