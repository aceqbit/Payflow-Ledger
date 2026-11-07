package com.payflow.ledger.support;

import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** Creates a throwaway, fully migrated Postgres database per test class (native PG; no Docker needed). */
public final class TestDb {
    public static final String ADMIN_URL = System.getProperty("pg.admin.url",
            System.getenv().getOrDefault("PG_TEST_ADMIN_URL", "jdbc:postgresql://localhost:5432/postgres"));
    public static final String USER = System.getProperty("pg.user", System.getenv().getOrDefault("PG_TEST_USER", "ledger"));
    public static final String PASSWORD = System.getProperty("pg.password", System.getenv().getOrDefault("PG_TEST_PASSWORD", "ledger"));

    public final String name;
    public final String url;

    private TestDb(String name, String url) {
        this.name = name;
        this.url = url;
    }

    public static TestDb create() {
        String name = "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create test database (is Postgres 18 running?)", e);
        }
        String url = ADMIN_URL.substring(0, ADMIN_URL.lastIndexOf('/') + 1) + name;
        Flyway.configure().dataSource(url, USER, PASSWORD).locations("classpath:db/migration").load().migrate();
        return new TestDb(name, url);
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, USER, PASSWORD);
    }

    public void drop() {
        try (Connection c = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        } catch (SQLException ignored) {
            // best effort cleanup
        }
    }
}
