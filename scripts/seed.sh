#!/usr/bin/env bash
# Builds the ledger_seed template database: Flyway migrations (via the app), then perf/seed.sql
# (100 merchants x 100 accounts, funded through real hash-chained entries), then exports accounts.csv.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

JAR=$(bash scripts/appjar.sh)
[ -f "$JAR" ] || { echo "build first: mvn -DskipTests package"; exit 1; }

$PSQL -d postgres -qc "DROP DATABASE IF EXISTS ledger_seed WITH (FORCE)"
$PSQL -d postgres -qc "CREATE DATABASE ledger_seed"
java -jar "$JAR" --ledger.migrate-only=true --server.port=0 --ledger.verify.enabled=false \
  --spring.profiles.active=telemetry-off \
  --spring.datasource.url="jdbc:postgresql://$PGHOST:$PGPORT/ledger_seed" > perf/results/migrate.log 2>&1
$PSQL -d ledger_seed -q -v ON_ERROR_STOP=1 -f perf/seed.sql
mkdir -p perf/results
echo "merchant_id,account_id" > perf/results/accounts.csv
$PSQL -d ledger_seed -At -F, -c "SELECT merchant_id, id FROM accounts WHERE NOT is_system ORDER BY merchant_id, id" >> perf/results/accounts.csv
echo "seeded: $($PSQL -d ledger_seed -At -c 'SELECT count(*) FROM accounts') accounts, $($PSQL -d ledger_seed -At -c 'SELECT count(*) FROM ledger_entries') entries"
