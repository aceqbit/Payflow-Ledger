# PayFlow Ledger v2 plan (condensed)

Source of truth: PayFlow Ledger v2 - Optimized Design & 2-Phase Plan (Nov 7, 2026).

## 2 Stack
Java 25 LTS + virtual threads; Spring Boot 4.0.x MVC; JdbcClient money path; PostgreSQL 18; Flyway; HikariCP; OAuth2 JWT resource server; springdoc; BIGINT minor units; SHA-256 hash chain; OTel starter -> Collector -> Prometheus/Tempo/Loki -> Grafana 12; JUnit5, Testcontainers, jqwik, Toxiproxy, Awaitility; Gatling.

## 3 Architecture
POST /v1/transfers: auth (merchant_id from JWT) -> idempotency insert (ON CONFLICT DO NOTHING; same hash replay, different hash 422) -> lock accounts ascending id, lock_timeout 2s -> post (2 entries multi-row insert, balances, hash_head, payment state, outbox, response) -> commit 201.
Schema: accounts(CHECK is_system OR balance>=0), payments(CHECK captured<=authorized, refunded<=captured), transactions(uuidv7, deferred sum-to-zero trigger), ledger_entries(append-only, amount<>0, idx account_id,created_at), idempotency_keys(partitioned by day), outbox.
Outbox: id uuidv7, aggregate_type, aggregate_id, type, seq, merchant_id, amount_minor, currency, payload jsonb, traceparent, occurred_at clock_timestamp(). ledger.outbox.mode retain|delete.
Hash: SHA-256(prev_hash || account_id(16B) || txn_id(16B) || amount(int64 BE) || created_at(epoch micros int64 BE)); genesis 32 zero bytes.
Strategies: PESSIMISTIC (FOR UPDATE, retry 40P01), OPTIMISTIC (version, retry 0 rows), SERIALIZABLE (retry 40001). Max 5 retries, full-jitter backoff from 2ms.

## 4-6 Observability
Metrics catalog (section 5): ledger_transfer_duration_seconds{strategy,outcome}, ledger_transfers_total, ledger_transfer_retries_total{strategy,reason}, ledger_transfer_attempts, ledger_lock_wait_seconds, ledger_db_roundtrips{operation}, ledger_idempotency_total{result}, ledger_idempotency_guard_seconds, ledger_payment_transitions_total{from,to,result}, ledger_outbox_writes_total{type}, ledger_hot_bucket_lock_seconds{buckets}, ledger_invariant_violations{check}, ledger_invariant_last_run_timestamp_seconds, ledger_chain_breaks{kind}, ledger_chain_entries_verified_total, ledger_chain_verify_seconds, ledger_tamper_injected{kind}.
SLOs: availability 99.9%, 99% transfers < 50ms. Alerts: InvariantViolation, InvariantCheckerStale, ChainBreakOutsideTamperTest, Availability fast/slow burn, LatencyFastBurn, Deadlocks, PoolStarved, ReplicationSlotLag.

## 7-9 Phases and targets
Phase 1: correct core + baseline. Phase 2: chaos, 3 strategies, 27-run matrix, tamper, hot buckets, tuning T1-T7, nightly CI.
Targets: duplicates 0, imbalance 0, negative 0, mismatch 0, tamper detection 100%, deadlocks 0; report-only: TPS 2-3x, p99<50ms, 1 round trip, verifier 100k/s, telemetry overhead <5%.
