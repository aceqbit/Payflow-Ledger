# Results

Machine: Windows 11, Intel64 Family 6 Model 186 Stepping 3, GenuineIntel, 12 logical CPUs, ? RAM, Postgres on the same host (no network)

Every number below is a Prometheus query over the measure window (first 60 s of each run discarded). Regenerate with `make report`.

| run_id | scenario | strategy | dist | users | telemetry | TPS | p50 ms | p99 ms | retry ratio | round-trips | pool acquire (p99 / mean ms) | invariants (max) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 20261107-135226-baseline-50u-PESSIMISTIC-uniform | baseline | PESSIMISTIC | uniform | 50 | on | 928.7 | 38.27 | 246.00 | 0.0000 | 8.00 | 100.00 / 33.441 | all 0 |
| 20261107-135827-baseline-200u-PESSIMISTIC-uniform | baseline | PESSIMISTIC | uniform | 200 | on | 1025.0 | 147.50 | 500.00 | 0.0000 | 8.00 | 100.00 / 173.878 | all 0 |
| 20261107-140451-baseline-500u-PESSIMISTIC-uniform | baseline | PESSIMISTIC | uniform | 500 | on | 468.4 | 22.85 | 500.00 | 0.0000 | 8.00 | 100.00 / 474.327 | all 0 |
| 20261107-141553-baseline-200u-PESSIMISTIC-uniform | baseline | PESSIMISTIC | uniform | 200 | off | n/a | n/a | n/a | n/a | n/a | n/a / n/a | global_sum=n/a, negative_balance=n/a, balance_mismatch=n/a, duplicate_charge=n/a |

Telemetry overhead at 200 users, client-side (Gatling) p99 on vs off: -56.5% (on 20261107-135827-baseline-200u-PESSIMISTIC-uniform: 1066.0 ms, off 20261107-141553-baseline-200u-PESSIMISTIC-uniform: 2448.0 ms); budget is under 5%. Client-side because the telemetry-off profile exports no server metrics.

Client-side (Gatling) view: run_id | p50 ms | p99 ms | req/s | failed

- 20261107-135226-baseline-50u-PESSIMISTIC-uniform: 42.0 | 249.0 | 913.3 | 0.0
- 20261107-135827-baseline-200u-PESSIMISTIC-uniform: 166.0 | 1066.0 | 973.1 | 0.0
- 20261107-140451-baseline-500u-PESSIMISTIC-uniform: 26.0 | 3972.0 | 479.4 | 0.0
- 20261107-141553-baseline-200u-PESSIMISTIC-uniform: 53.0 | 2448.0 | 584.7 | 0.0
