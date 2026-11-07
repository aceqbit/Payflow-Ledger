# Results

Machine: Windows 11, Intel64 Family 6 Model 186 Stepping 3, GenuineIntel, 12 logical CPUs, ? RAM, Postgres on the same host (no network)

Every number below is a Prometheus query over the measure window (first 60 s of each run discarded). Regenerate with `make report`.

| run_id | scenario | strategy | dist | users | telemetry | TPS | p50 ms | p99 ms | retry ratio | round-trips | pool acquire (p99 / mean ms) | invariants (max) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 20261107-135226-baseline-50u-PESSIMISTIC-uniform | baseline | PESSIMISTIC | uniform | 50 | on | 928.7 | 38.27 | 246.00 | 0.0000 | 8.00 | 100.00 / 33.441 | all 0 |
| 20261107-135827-baseline-200u-PESSIMISTIC-uniform | baseline | PESSIMISTIC | uniform | 200 | on | 1025.0 | 147.50 | 500.00 | 0.0000 | 8.00 | 100.00 / 173.878 | all 0 |
| 20261107-140451-baseline-500u-PESSIMISTIC-uniform | baseline | PESSIMISTIC | uniform | 500 | on | 468.4 | 22.85 | 500.00 | 0.0000 | 8.00 | 100.00 / 474.327 | all 0 |
| 20261107-141553-baseline-200u-PESSIMISTIC-uniform | baseline | PESSIMISTIC | uniform | 200 | off | n/a | n/a | n/a | n/a | n/a | n/a / n/a | global_sum=n/a, negative_balance=n/a, balance_mismatch=n/a, duplicate_charge=n/a |
| 20261107-161819-tune-T4-before-200u-PESSIMISTIC-uniform | tune-T4-before | PESSIMISTIC | uniform | 200 | on | 730.1 | 135.90 | 500.00 | 0.0000 | 8.00 | 100.00 / 248.420 | all 0 |
| 20261107-162034-tune-T4-before-200u-PESSIMISTIC-hot | tune-T4-before | PESSIMISTIC | hot | 200 | on | 362.6 | 500.00 | 500.00 | 0.0000 | 8.00 | 100.00 / 507.021 | all 0 |
| 20261107-162248-tune-T4-after-200u-PESSIMISTIC-uniform | tune-T4-after | PESSIMISTIC | uniform | 200 | on | 1051.7 | 158.40 | 500.00 | 0.0000 | 1.00 | 100.00 / 167.656 | all 0 |
| 20261107-162508-tune-T4-after-200u-PESSIMISTIC-hot | tune-T4-after | PESSIMISTIC | hot | 200 | on | 546.2 | 387.42 | 500.00 | 0.0000 | 1.00 | 100.00 / 334.208 | all 0 |
| 20261107-162726-tune-TB-before-200u-PESSIMISTIC-uniform | tune-TB-before | PESSIMISTIC | uniform | 200 | on | 1220.1 | 131.84 | 500.00 | 0.0000 | 1.00 | 100.00 / 145.740 | all 0 |
| 20261107-162941-tune-TB-before-200u-PESSIMISTIC-hot | tune-TB-before | PESSIMISTIC | hot | 200 | on | 539.8 | 382.77 | 500.00 | 0.0000 | 1.00 | 100.00 / 334.371 | all 0 |
| 20261107-163156-tune-T2_32-after-200u-PESSIMISTIC-uniform | tune-T2_32-after | PESSIMISTIC | uniform | 200 | on | 1268.6 | 64.83 | 500.00 | 0.0000 | 1.00 | 100.00 / 122.496 | all 0 |
| 20261107-163408-tune-T2_32-after-200u-PESSIMISTIC-hot | tune-T2_32-after | PESSIMISTIC | hot | 200 | on | 559.2 | 362.75 | 500.00 | 0.0000 | 1.00 | 100.00 / 292.320 | all 0 |
| 20261107-163621-tune-T3-after-200u-PESSIMISTIC-uniform | tune-T3-after | PESSIMISTIC | uniform | 200 | on | 1439.5 | 26.21 | 500.00 | 0.0000 | 1.00 | 100.00 / 125.971 | all 0 |
| 20261107-163833-tune-T3-after-200u-PESSIMISTIC-hot | tune-T3-after | PESSIMISTIC | hot | 200 | on | 579.0 | 363.59 | 500.00 | 0.0000 | 1.00 | 100.00 / 314.398 | all 0 |
| 20261107-164044-tune-T7-after-200u-PESSIMISTIC-uniform | tune-T7-after | PESSIMISTIC | uniform | 200 | on | 364.2 | 198.41 | 500.00 | 0.0000 | 1.00 | 100.00 / 479.543 | all 0 |
| 20261107-164305-tune-T7-after-200u-PESSIMISTIC-hot | tune-T7-after | PESSIMISTIC | hot | 200 | on | 325.1 | 471.70 | 500.00 | 0.0000 | 1.00 | 100.00 / 510.014 | all 0 |
| 20261107-164535-tune-T4-before-200u-PESSIMISTIC-uniform | tune-T4-before | PESSIMISTIC | uniform | 200 | on | 683.5 | 140.08 | 500.00 | 0.0000 | 8.00 | 100.00 / 253.406 | all 0 |
| 20261107-164757-tune-T4-before-200u-PESSIMISTIC-hot | tune-T4-before | PESSIMISTIC | hot | 200 | on | 307.3 | 500.00 | 500.00 | 0.0000 | 8.00 | 100.00 / 597.310 | all 0 |
| 20261107-165015-tune-T4-after-200u-PESSIMISTIC-uniform | tune-T4-after | PESSIMISTIC | uniform | 200 | on | 871.0 | 128.62 | 500.00 | 0.0000 | 1.00 | 100.00 / 195.217 | all 0 |
| 20261107-165243-tune-T4-after-200u-PESSIMISTIC-hot | tune-T4-after | PESSIMISTIC | hot | 200 | on | 407.4 | 429.83 | 500.00 | 0.0000 | 1.00 | 100.00 / 453.894 | all 0 |
| 20261107-165503-tune-TB-before-200u-PESSIMISTIC-uniform | tune-TB-before | PESSIMISTIC | uniform | 200 | on | 1044.2 | 159.51 | 500.00 | 0.0000 | 1.00 | 100.00 / 169.970 | all 0 |
| 20261107-165726-tune-TB-before-200u-PESSIMISTIC-hot | tune-TB-before | PESSIMISTIC | hot | 200 | on | 298.3 | 500.00 | 500.00 | 0.0000 | 1.00 | 100.00 / 585.287 | all 0 |
| 20261107-165950-tune-T2_32-after-200u-PESSIMISTIC-uniform | tune-T2_32-after | PESSIMISTIC | uniform | 200 | on | 1223.2 | 46.59 | 500.00 | 0.0000 | 1.00 | 100.00 / 122.393 | all 0 |
| 20261107-170216-tune-T2_32-after-200u-PESSIMISTIC-hot | tune-T2_32-after | PESSIMISTIC | hot | 200 | on | 346.1 | 456.54 | 500.00 | 0.0000 | 1.00 | 100.00 / 470.654 | all 0 |
| 20261107-170433-tune-T3-after-200u-PESSIMISTIC-uniform | tune-T3-after | PESSIMISTIC | uniform | 200 | on | 1230.8 | 80.22 | 500.00 | 0.0000 | 1.00 | 100.00 / 148.604 | all 0 |
| 20261107-170657-tune-T3-after-200u-PESSIMISTIC-hot | tune-T3-after | PESSIMISTIC | hot | 200 | on | 374.1 | 452.19 | 500.00 | 0.0000 | 1.00 | 100.00 / 481.076 | all 0 |
| 20261107-170920-tune-T7-after-200u-PESSIMISTIC-uniform | tune-T7-after | PESSIMISTIC | uniform | 200 | on | 854.5 | 154.61 | 500.00 | 0.0000 | 1.00 | 100.00 / 212.112 | all 0 |
| 20261107-171145-tune-T7-after-200u-PESSIMISTIC-hot | tune-T7-after | PESSIMISTIC | hot | 200 | on | 526.5 | 396.34 | 500.00 | 0.0000 | 1.00 | 100.00 / 347.977 | all 0 |
| 20261107-171419-hot-N1-200u-PESSIMISTIC-hot | hot-N1 | PESSIMISTIC | hot | 200 | on | 335.0 | 500.00 | 500.00 | 0.0000 | 9.00 | 100.00 / 546.036 | all 0 |
| 20261107-171637-hot-N4-200u-PESSIMISTIC-hot | hot-N4 | PESSIMISTIC | hot | 200 | on | 331.3 | 500.00 | 500.00 | 0.0000 | 9.00 | 100.00 / 551.912 | all 0 |
| 20261107-171852-hot-N16-200u-PESSIMISTIC-hot | hot-N16 | PESSIMISTIC | hot | 200 | on | 330.6 | 500.00 | 500.00 | 0.0000 | 8.99 | 100.00 / 557.049 | all 0 |
| 20261107-172113-hot-N64-200u-PESSIMISTIC-hot | hot-N64 | PESSIMISTIC | hot | 200 | on | 349.7 | 500.00 | 500.00 | 0.0000 | 8.99 | 100.00 / 524.104 | all 0 |

Telemetry overhead at 200 users, client-side (Gatling) p99 on vs off: -51.9% (on 20261107-170920-tune-T7-after-200u-PESSIMISTIC-uniform: 1177.0 ms, off 20261107-141553-baseline-200u-PESSIMISTIC-uniform: 2448.0 ms); budget is under 5%. Client-side because the telemetry-off profile exports no server metrics.

Client-side (Gatling) view: run_id | p50 ms | p99 ms | req/s | failed

- 20261107-135226-baseline-50u-PESSIMISTIC-uniform: 42.0 | 249.0 | 913.3 | 0.0
- 20261107-135827-baseline-200u-PESSIMISTIC-uniform: 166.0 | 1066.0 | 973.1 | 0.0
- 20261107-140451-baseline-500u-PESSIMISTIC-uniform: 26.0 | 3972.0 | 479.4 | 0.0
- 20261107-141553-baseline-200u-PESSIMISTIC-uniform: 53.0 | 2448.0 | 584.7 | 0.0
- 20261107-161819-tune-T4-before-200u-PESSIMISTIC-uniform: 203.0 | 1810.0 | 667.0 | 0.0
- 20261107-162034-tune-T4-before-200u-PESSIMISTIC-hot: 507.0 | 1660.0 | 339.3 | 0.0
- 20261107-162248-tune-T4-after-200u-PESSIMISTIC-uniform: 143.0 | 1059.0 | 1030.3 | 0.0
- 20261107-162508-tune-T4-after-200u-PESSIMISTIC-hot: 317.0 | 1237.0 | 514.0 | 0.0
- 20261107-162726-tune-TB-before-200u-PESSIMISTIC-uniform: 123.0 | 1032.0 | 1138.3 | 0.0
- 20261107-162941-tune-TB-before-200u-PESSIMISTIC-hot: 315.0 | 1320.0 | 506.8 | 0.0
- 20261107-163156-tune-T2_32-after-200u-PESSIMISTIC-uniform: 93.0 | 1021.0 | 1183.0 | 0.0
- 20261107-163408-tune-T2_32-after-200u-PESSIMISTIC-hot: 290.0 | 1386.0 | 523.6 | 0.0
- 20261107-163621-tune-T3-after-200u-PESSIMISTIC-uniform: 29.0 | 1400.0 | 1332.3 | 0.0
- 20261107-163833-tune-T3-after-200u-PESSIMISTIC-hot: 295.0 | 1365.0 | 538.7 | 0.0
- 20261107-164044-tune-T7-after-200u-PESSIMISTIC-uniform: 259.0 | 4080.0 | 348.6 | 0.0
- 20261107-164305-tune-T7-after-200u-PESSIMISTIC-hot: 468.0 | 2689.0 | 345.2 | 0.0
- 20261107-164535-tune-T4-before-200u-PESSIMISTIC-uniform: 191.0 | 1699.0 | 691.7 | 0.0
- 20261107-164757-tune-T4-before-200u-PESSIMISTIC-hot: 569.0 | 2522.0 | 293.1 | 0.0
- 20261107-165015-tune-T4-after-200u-PESSIMISTIC-uniform: 146.0 | 1316.0 | 868.0 | 0.0
- 20261107-165243-tune-T4-after-200u-PESSIMISTIC-hot: 407.0 | 1782.0 | 389.5 | 0.0
- 20261107-165503-tune-TB-before-200u-PESSIMISTIC-uniform: 146.0 | 1090.0 | 994.0 | 0.0
- 20261107-165726-tune-TB-before-200u-PESSIMISTIC-hot: 530.0 | 2932.0 | 301.9 | 0.0
- 20261107-165950-tune-T2_32-after-200u-PESSIMISTIC-uniform: 61.0 | 1094.0 | 1187.5 | 0.0
- 20261107-170216-tune-T2_32-after-200u-PESSIMISTIC-hot: 429.0 | 2796.0 | 327.7 | 0.0
- 20261107-170433-tune-T3-after-200u-PESSIMISTIC-uniform: 96.0 | 1653.0 | 1168.1 | 0.0
- 20261107-170657-tune-T3-after-200u-PESSIMISTIC-hot: 452.0 | 1983.0 | 366.8 | 0.0
- 20261107-170920-tune-T7-after-200u-PESSIMISTIC-uniform: 201.0 | 1177.0 | 827.9 | 0.0
- 20261107-171145-tune-T7-after-200u-PESSIMISTIC-hot: 333.0 | 1360.0 | 488.0 | 0.0
- 20261107-171419-hot-N1-200u-PESSIMISTIC-hot: 523.0 | 2284.0 | 324.5 | 0.0
- 20261107-171637-hot-N4-200u-PESSIMISTIC-hot: 563.0 | 1930.0 | 309.3 | 0.0
- 20261107-171852-hot-N16-200u-PESSIMISTIC-hot: 554.0 | 1882.0 | 316.8 | 0.0
- 20261107-172113-hot-N64-200u-PESSIMISTIC-hot: 547.0 | 1769.0 | 325.2 | 0.0

## Hot-account buckets (PESSIMISTIC, 1 hot account, TPS vs N)

| N | users | TPS | p99 ms | lock wait ok | invariants | run_id |
|---|---|---|---|---|---|---|
| 1 | 200 | 335.0 | 500.00 | - | all 0 | 20261107-171419-hot-N1-200u-PESSIMISTIC-hot |
| 4 | 200 | 331.3 | 500.00 | - | all 0 | 20261107-171637-hot-N4-200u-PESSIMISTIC-hot |
| 16 | 200 | 330.6 | 500.00 | - | all 0 | 20261107-171852-hot-N16-200u-PESSIMISTIC-hot |
| 64 | 200 | 349.7 | 500.00 | - | all 0 | 20261107-172113-hot-N64-200u-PESSIMISTIC-hot |

TPS at N=16 is 0.99x N=1 (target: 5x or more).

## Tuning loop

Each cell is the mean of the interleaved runs for that variant (n shown). TPS is the server-side Prometheus rate over the measure window; p50/p99 are client-side (Gatling) because the server histogram tops out at 500 ms. The shared laptop also ran an unrelated Kafka/dual-write workload during these runs, so run-to-run noise is large; treat gains under about 10% as inconclusive.

Rule: keep a change only if every invariant stays 0 and TPS or p99 improves by 5% or more without the other getting more than 5% worse.

| step | change | skew | n | TPS before -> after | client p50 ms before -> after | client p99 ms before -> after | round-trips before -> after | decision | before runs | after runs |
|---|---|---|---|---|---|---|---|---|---|---|
| T2_32 | pool=32 (on function path) | uniform | 2/2 | 1132.2 -> 1245.9 (+10.0%) | 134.5 -> 77.0 | 1061.0 -> 1057.5 (-0.3%) | 1.00 -> 1.00 | kept | 20261107-162726-tune-TB-before-200u-PESSIMISTIC-uniform, 20261107-165503-tune-TB-before-200u-PESSIMISTIC-uniform | 20261107-163156-tune-T2_32-after-200u-PESSIMISTIC-uniform, 20261107-165950-tune-T2_32-after-200u-PESSIMISTIC-uniform |
| T2_32 | pool=32 (on function path) | hot | 2/2 | 419.1 -> 452.7 (+8.0%) | 422.5 -> 359.5 | 2126.0 -> 2091.0 (-1.6%) | 1.00 -> 1.00 | kept | 20261107-162941-tune-TB-before-200u-PESSIMISTIC-hot, 20261107-165726-tune-TB-before-200u-PESSIMISTIC-hot | 20261107-163408-tune-T2_32-after-200u-PESSIMISTIC-hot, 20261107-170216-tune-T2_32-after-200u-PESSIMISTIC-hot |
| T3 | platform threads instead of virtual (on function path) | uniform | 2/2 | 1132.2 -> 1335.2 (+17.9%) | 134.5 -> 62.5 | 1061.0 -> 1526.5 (+43.9%) | 1.00 -> 1.00 | reverted | 20261107-162726-tune-TB-before-200u-PESSIMISTIC-uniform, 20261107-165503-tune-TB-before-200u-PESSIMISTIC-uniform | 20261107-163621-tune-T3-after-200u-PESSIMISTIC-uniform, 20261107-170433-tune-T3-after-200u-PESSIMISTIC-uniform |
| T3 | platform threads instead of virtual (on function path) | hot | 2/2 | 419.1 -> 476.6 (+13.7%) | 422.5 -> 373.5 | 2126.0 -> 1674.0 (-21.3%) | 1.00 -> 1.00 | kept | 20261107-162941-tune-TB-before-200u-PESSIMISTIC-hot, 20261107-165726-tune-TB-before-200u-PESSIMISTIC-hot | 20261107-163833-tune-T3-after-200u-PESSIMISTIC-hot, 20261107-170657-tune-T3-after-200u-PESSIMISTIC-hot |
| T4 | post_transfer() single SQL function | uniform | 2/2 | 706.8 -> 961.3 (+36.0%) | 197.0 -> 144.5 | 1754.5 -> 1187.5 (-32.3%) | 8.00 -> 1.00 | kept | 20261107-161819-tune-T4-before-200u-PESSIMISTIC-uniform, 20261107-164535-tune-T4-before-200u-PESSIMISTIC-uniform | 20261107-162248-tune-T4-after-200u-PESSIMISTIC-uniform, 20261107-165015-tune-T4-after-200u-PESSIMISTIC-uniform |
| T4 | post_transfer() single SQL function | hot | 2/2 | 334.9 -> 476.8 (+42.4%) | 538.0 -> 362.0 | 2091.0 -> 1509.5 (-27.8%) | 8.00 -> 1.00 | kept | 20261107-162034-tune-T4-before-200u-PESSIMISTIC-hot, 20261107-164757-tune-T4-before-200u-PESSIMISTIC-hot | 20261107-162508-tune-T4-after-200u-PESSIMISTIC-hot, 20261107-165243-tune-T4-after-200u-PESSIMISTIC-hot |
| T7 | Postgres group commit: commit_delay=1ms, commit_siblings=5 (on function path) | uniform | 2/2 | 1132.2 -> 609.4 (-46.2%) | 134.5 -> 230.0 | 1061.0 -> 2628.5 (+147.7%) | 1.00 -> 1.00 | reverted | 20261107-162726-tune-TB-before-200u-PESSIMISTIC-uniform, 20261107-165503-tune-TB-before-200u-PESSIMISTIC-uniform | 20261107-164044-tune-T7-after-200u-PESSIMISTIC-uniform, 20261107-170920-tune-T7-after-200u-PESSIMISTIC-uniform |
| T7 | Postgres group commit: commit_delay=1ms, commit_siblings=5 (on function path) | hot | 2/2 | 419.1 -> 425.8 (+1.6%) | 422.5 -> 400.5 | 2126.0 -> 2024.5 (-4.8%) | 1.00 -> 1.00 | reverted | 20261107-162941-tune-TB-before-200u-PESSIMISTIC-hot, 20261107-165726-tune-TB-before-200u-PESSIMISTIC-hot | 20261107-164305-tune-T7-after-200u-PESSIMISTIC-hot, 20261107-171145-tune-T7-after-200u-PESSIMISTIC-hot |
