#!/usr/bin/env bash
# One bench run: fresh DB from the seed template, app restarted with RUN_ID/SCENARIO, Gatling load, verify.
# usage: scripts/run.sh <scenario> <users> [strategy=PESSIMISTIC] [dist=uniform] [duration=300] [extra profiles]
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

SCENARIO=${1:?scenario}; USERS=${2:?users}; STRATEGY=${3:-PESSIMISTIC}; DIST=${4:-uniform}; DURATION=${5:-300}
EXTRA_PROFILE=${6:-}
export SCENARIO
export RUN_ID="$(date +%Y%m%d-%H%M%S)-${SCENARIO}-${USERS}u-${STRATEGY}-${DIST}"
PROFILES="$APP_PROFILES${EXTRA_PROFILE:+,$EXTRA_PROFILE}"
PROFILES=${PROFILES#,}
JAR=$(ls target/payflow-ledger-*.jar | grep -v original | head -1)
OUT=perf/results; mkdir -p $OUT
echo "== $RUN_ID (profiles: ${PROFILES:-none})"

# 1. fresh DB from template (seconds, not minutes)
$PSQL -d postgres -qc "DROP DATABASE IF EXISTS ledger_run WITH (FORCE)"
$PSQL -d postgres -qc "CREATE DATABASE ledger_run TEMPLATE ledger_seed"

# 2. app with run tags
DB_URL="jdbc:postgresql://$PGHOST:$PGPORT/ledger_run" \
  java ${JAVA_OPTS:-} -jar "$JAR" --ledger.strategy="$STRATEGY" --ledger.verify.enabled=false \
  ${PROFILES:+--spring.profiles.active=$PROFILES} ${APP_ARGS:-} > "$OUT/app-$RUN_ID.log" 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true; wait $APP_PID 2>/dev/null || true' EXIT
for _ in $(seq 1 90); do curl -fs "$APP/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
curl -fs "$APP/actuator/health" >/dev/null || { echo "app did not start, see $OUT/app-$RUN_ID.log"; exit 1; }

# 3. load
START=$(date +%s)
(cd perf && mvn -q gatling:test -Dgatling.simulationClass=perf.TransferSimulation \
   -Dusers="$USERS" -Dduration="$DURATION" -Ddist="$DIST" -Daccounts=results/accounts.csv \
   -Dgatling.resultsFolder="$PWD/results/gatling" > "../$OUT/gatling-$RUN_ID.log" 2>&1) || { echo "gatling failed, see $OUT/gatling-$RUN_ID.log"; exit 1; }
END=$(date +%s)

# 4. verify so the invariant gauges carry this run_id, then let the exporter flush
TOKEN=$($PYTHON scripts/mint_token.py admin admin)
curl -fs -X POST -H "Authorization: Bearer $TOKEN" "$APP/admin/verify" > "$OUT/verify-$RUN_ID.json"
sleep 12
VERIFIED=$(date +%s)

cat > "$OUT/$RUN_ID.json" <<EOF
{"run_id":"$RUN_ID","scenario":"$SCENARIO","users":$USERS,"strategy":"$STRATEGY","dist":"$DIST","duration_s":$DURATION,
 "warmup_s":60,"start_epoch":$START,"end_epoch":$END,"verified_epoch":$VERIFIED,"profiles":"${PROFILES}"}
EOF
echo "done: $RUN_ID"
