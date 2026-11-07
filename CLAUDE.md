# PayFlow Ledger

Idempotent double-entry payment core. Goal: prove 0 duplicate charges, 0 ledger imbalance and 100% tamper detection under injected failure, and measure what each design choice costs. Design: docs/plan.md. Progress: docs/progress.md.

## Stack (pinned, do not upgrade mid-phase)
- Java 25, virtual threads on. Spring Boot 4.0.x (add spring-boot-starter-flyway, spring-boot-starter-opentelemetry explicitly). Jackson 3.
- PostgreSQL 18 (wal_level=logical, pg_stat_statements), Flyway, HikariCP.
- Money path: JdbcClient with explicit SQL. JPA only for admin reads.
- Telemetry: OTLP -> Collector -> Prometheus 3, Tempo, Loki, Grafana.
- Tests: JUnit 5, Testcontainers (postgres:18, toxiproxy), jqwik, Awaitility. Load: Gatling Java DSL.

## Commands
make up | down | test | it | bench | chaos | tamper | report

## Money rules (never break, never weaken to pass a test)
1. One Postgres transaction per request writes: idempotency row, both entries, both balances, hash_head, payment state, outbox row, stored response.
2. Amounts are BIGINT minor units + CHAR(3) currency. No double/float/BigDecimal on the money path.
3. Entries of a transaction sum to 0 (deferred trigger). ledger_entries is append-only (trigger).
4. Lock accounts FOR UPDATE in ascending id order. SET LOCAL lock_timeout = '2s'.
5. merchant_id comes from the JWT only.
6. Idempotency: INSERT ... ON CONFLICT DO NOTHING inside the money transaction. Same hash replays; different hash 422.
7. Payment states change only through the PaymentState transition table. Illegal moves 409.
8. Hash chain uses the fixed binary encoding in HashChain; Java and SQL ledger_entry_hash() stay byte-identical.
9. Outbox columns are a contract with PayFlow Events. Changes need a new migration and a progress.md note.
10. Never edit an applied Flyway migration.

## Conventions
- All custom meters live in telemetry/LedgerMetrics. Never label by account/merchant/payment/key ids.
- Latency histograms with SLO buckets 5,10,25,50,100,250,500 ms.
- Dashboards, alerts, Collector config are files in deploy/observability/.
- jqwik tries: -Djqwik.tries.default=N (surefire passes it; 10000 on push, 10000000 nightly).
- Bench numbers come only from make report. One prompt = one commit: `<prompt-id>: <what changed>`.
