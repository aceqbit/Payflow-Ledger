package com.payflow.ledger.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Money-path entry to JdbcClient. Every sql() call is one database round-trip, counted per
 * request thread so ledger.db.roundtrips reflects what the transfer really cost.
 */
@Component
public class CountingJdbc {
    private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);
    private final JdbcClient jdbc;

    public CountingJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcClient.StatementSpec sql(String sql) {
        COUNT.get()[0]++;
        return jdbc.sql(sql);
    }

    public static void reset() {
        COUNT.get()[0] = 0;
    }

    public static int count() {
        return COUNT.get()[0];
    }
}
