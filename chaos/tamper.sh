#!/usr/bin/env bash
# make tamper: grow the ledger to ~1M entries, apply 1,000 mutations (334 edits, 333 deletes, 333 reorders) with the
# append-only trigger disabled as superuser, then run the parallel ChainVerifier. Prints detected/injected per kind and
# entries/s. Exits non-zero unless every kind is detected 100% and the verifier reaches 100k entries/s.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

ENTRIES=${ENTRIES:-1000000}
export SCENARIO=tamper
export RUN_ID="$(date +%Y%m%d-%H%M%S)-tamper"
JAR=$(ls target/payflow-ledger-*.jar | grep -v original | head -1)
OUT=perf/results; mkdir -p $OUT

$PSQL -d postgres -qc "DROP DATABASE IF EXISTS ledger_tamper WITH (FORCE)"
$PSQL -d postgres -qc "CREATE DATABASE ledger_tamper TEMPLATE ledger_seed"
$PSQL -d ledger_tamper -q -f chaos/tamper.sql
NEED=$(( (ENTRIES - $($PSQL -d ledger_tamper -At -c 'SELECT count(*) FROM ledger_entries')) / 2 ))
echo "seeding $NEED more transfers..."
$PSQL -d ledger_tamper -q -c "SELECT chaos_seed_entries($NEED)"
echo "entries: $($PSQL -d ledger_tamper -At -c 'SELECT count(*) FROM ledger_entries')"

DB_URL="jdbc:postgresql://$PGHOST:$PGPORT/ledger_tamper" \
  java -jar "$JAR" --ledger.verify.enabled=false ${APP_PROFILES:+--spring.profiles.active=$APP_PROFILES} > "$OUT/app-$RUN_ID.log" 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true; wait $APP_PID 2>/dev/null || true' EXIT
for _ in $(seq 1 90); do curl -fs "$APP/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
TOKEN=$($PYTHON scripts/mint_token.py admin admin)
post() { curl -fs -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "$APP$1" ${2:+-d "$2"}; }

echo "clean verification first (must show 0 breaks)..."
CLEAN=$(post /admin/verify)
echo "$CLEAN" | $PYTHON -c "import json,sys; d=json.load(sys.stdin); assert sum(d['chainBreaks'].values())==0, d; print('clean ok, entries', d['chainEntriesVerified'])"

echo "tampering: 334 edits, 333 deletes, 333 reorders"
$PSQL -d ledger_tamper -q -c "SELECT chaos_tamper(334, 333, 333)"
post /admin/tamper-injected '{"edit":334,"delete":333,"reorder":333}' > /dev/null
RESULT=$(post /admin/verify)
echo "$RESULT" > "$OUT/tamper-$RUN_ID.verify.json"
echo "$RESULT" | $PYTHON -c "
import json, sys
d = json.load(sys.stdin)
inj = {'edit': 334, 'delete': 333, 'reorder': 333}
ok = True
for k, n in inj.items():
    got = d['chainBreaks'][k]
    pct = 100.0 * got / n
    print(f'{k:8s} detected {got}/{n} = {pct:.1f}%')
    ok &= (got == n)
eps = d['chainEntriesPerSecond']
print(f'verifier: {d[\"chainEntriesVerified\"]} entries in {d[\"chainVerifySeconds\"]:.2f}s = {eps:,.0f} entries/s (target >= 100,000)')
print('TAMPER PASS' if ok and eps >= 100000 else 'TAMPER FAIL')
sys.exit(0 if ok and eps >= 100000 else 1)
"
