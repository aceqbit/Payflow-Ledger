#!/usr/bin/env bash
# make chaos: for each profile (latency, reset_peer, timeout) run CHAOS_REQUESTS transfers through Toxiproxy while the
# app is kill -9'd every 10-30 s, retrying every failure with the same Idempotency-Key. Then verify and print PASS/FAIL per
# correctness metric. Exits non-zero on any FAIL.  CHAOS_REQUESTS defaults to 1,000,000; PROFILES to all three.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
export TAIL_SAMPLE_PERCENT=100   # compose Collector keeps every trace during chaos
REQUESTS=${CHAOS_REQUESTS:-1000000}
PROFILES=${PROFILES:-"latency reset_peer timeout"}
OUT=perf/results; mkdir -p $OUT
FAILED=0

bash chaos/toxiproxy.sh start
trap 'bash chaos/toxiproxy.sh stop; bash chaos/app.sh kill' EXIT

for PROFILE in $PROFILES; do
  export SCENARIO="chaos-$PROFILE"
  export RUN_ID="$(date +%Y%m%d-%H%M%S)-chaos-$PROFILE"
  echo "== $RUN_ID: $REQUESTS requests, 5% faults/kill -9 every 10-30 s"
  $PSQL -d postgres -qc "DROP DATABASE IF EXISTS ledger_chaos WITH (FORCE)"
  $PSQL -d postgres -qc "CREATE DATABASE ledger_chaos TEMPLATE ledger_seed"
  bash chaos/toxiproxy.sh setup
  bash chaos/toxiproxy.sh "$PROFILE"
  bash chaos/app.sh start
  for _ in $(seq 1 90); do curl -fs "$APP/actuator/health" > /dev/null 2>&1 && break; sleep 1; done

  bash chaos/killer.sh "$OUT/kills-$RUN_ID.log" &
  KILLER=$!
  START=$(date +%s)
  (cd perf && mvn -q gatling:test -Dgatling.simulationClass=perf.ChaosSimulation -Drequests="$REQUESTS" -Daccounts=results/accounts.csv \
     -Dgatling.resultsFolder="$PWD/results/gatling" > "../$OUT/gatling-$RUN_ID.log" 2>&1)
  END=$(date +%s)
  kill $KILLER 2>/dev/null; wait $KILLER 2>/dev/null
  KILLS=$(wc -l < "$OUT/kills-$RUN_ID.log" | tr -d ' ')

  # clean network, app up, then verify against the real ledger
  bash chaos/toxiproxy.sh clear
  bash chaos/app.sh kill; sleep 1; bash chaos/app.sh start
  for _ in $(seq 1 90); do curl -fs "$APP/actuator/health" > /dev/null 2>&1 && break; sleep 1; done
  TOKEN=$($PYTHON scripts/mint_token.py admin admin)
  RESULT=$(curl -fs -X POST -H "Authorization: Bearer $TOKEN" "$APP/admin/verify") || { echo "verify call failed"; FAILED=1; continue; }
  echo "$RESULT" > "$OUT/verify-$RUN_ID.json"
  sleep 12; VERIFIED=$(date +%s)
  DEADLOCKS=$($PSQL -d postgres -At -c "SELECT deadlocks FROM pg_stat_database WHERE datname='ledger_chaos'")
  TRANSFERS=$($PSQL -d ledger_chaos -At -c "SELECT count(*) FROM transactions WHERE type='transfer'")
  KEYS=$($PSQL -d ledger_chaos -At -c "SELECT count(*) FROM idempotency_keys WHERE response_code = 201")

  echo "$RESULT" | $PYTHON -c "
import json, sys
d = json.load(sys.stdin)['invariants']
bad = 0
for k in ('duplicate_charge', 'global_sum', 'negative_balance', 'balance_mismatch'):
    ok = d[k] == 0
    bad += not ok
    print(f'  {k:18s} {\"PASS\" if ok else \"FAIL\"} ({d[k]})')
dl = int('$DEADLOCKS'); ok = dl == 0; bad += not ok
print(f'  {\"deadlocks\":18s} {\"PASS\" if ok else \"FAIL\"} ({dl})')
t, k = int('$TRANSFERS'), int('$KEYS'); ok = t == k; bad += not ok
print(f'  {\"one txn per key\":18s} {\"PASS\" if ok else \"FAIL\"} ({t} transfers, {k} keys stored with 201)')
kills = int('$KILLS'); print(f'  kills: {kills}')
sys.exit(1 if bad else 0)
" || FAILED=1

  cat > "$OUT/$RUN_ID.json" <<EOF
{"run_id":"$RUN_ID","scenario":"$SCENARIO","users":50,"strategy":"PESSIMISTIC","dist":"uniform","duration_s":$((END-START)),
 "warmup_s":0,"start_epoch":$START,"end_epoch":$END,"verified_epoch":$VERIFIED,"profiles":"${APP_PROFILES}","kills":$KILLS,"requests":$REQUESTS}
EOF
done
[ $FAILED -eq 0 ] && echo "CHAOS PASS" || { echo "CHAOS FAIL"; exit 1; }
