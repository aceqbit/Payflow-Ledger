# Locking verdict (reduced matrix)

Scope: 3 strategies x 200 users x {uniform, one hot account}, 75 s runs (not the full 27-run matrix). Numbers are Prometheus
queries from `docs/results.json`; the laptop also ran unrelated load, so differences under about 10% are noise.

| skew | winner | evidence (run ids in docs/results.md) |
|---|---|---|
| hot account | PESSIMISTIC | 316.7 TPS vs 167.6 (OPTIMISTIC) and 140.0 (SERIALIZABLE), run `20261107-181104-matrix-200u-PESSIMISTIC-hot`. The other two retry 2.59 and 2.71 times per transfer (retry ratio), PESSIMISTIC retries 0. |
| uniform | tie, SERIALIZABLE last | OPTIMISTIC 764.1 and PESSIMISTIC 660.6 TPS are within noise; SERIALIZABLE 565.9 TPS with retry ratio 0.12. |

Rule for production: use PESSIMISTIC (ordered `FOR UPDATE`, one SQL function call) by default because it never retries and stays
correct under skew. OPTIMISTIC only for known-uniform traffic. Avoid SERIALIZABLE for the money path: it has the lowest TPS and
under 500 threads on 50 accounts it exhausted its 5 retries 1,569 times in the concurrency test.

Invariants were 0 in every run (all four checks). Caveat: OPTIMISTIC and SERIALIZABLE run the Java path (8 round-trips), PESSIMISTIC
runs the T4 SQL function (1 round-trip), so part of PESSIMISTIC's lead is the round-trip reduction, not only the locking mode.
