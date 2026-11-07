package com.payflow.ledger.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/** Shared property wiring for integration tests that run the app against a throwaway native Postgres. */
public final class SpringDbTest {
    private SpringDbTest() {}

    public static void wire(DynamicPropertyRegistry r, TestDb db) {
        r.add("spring.datasource.url", () -> db.url);
        r.add("spring.datasource.username", () -> TestDb.USER);
        r.add("spring.datasource.password", () -> TestDb.PASSWORD);
        r.add("management.otlp.metrics.export.enabled", () -> "false");
        r.add("ledger.verify.enabled", () -> "false");
    }
}
