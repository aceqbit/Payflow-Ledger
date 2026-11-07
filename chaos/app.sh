#!/usr/bin/env bash
# Start or kill -9 the ledger app for chaos runs.   usage: chaos/app.sh start|kill
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
JAR=$(ls target/payflow-ledger-*.jar | grep -v original | head -1)

case "${1:?usage}" in
  start)
    DB_URL="jdbc:postgresql://localhost:15432/ledger_chaos?connectTimeout=5&socketTimeout=30" \
      nohup java -jar "$JAR" --ledger.verify.enabled=false ${APP_PROFILES:+--spring.profiles.active=$APP_PROFILES} \
      >> "perf/results/app-${RUN_ID:-chaos}.log" 2>&1 &
    ;;
  kill)
    if command -v powershell >/dev/null 2>&1; then
      # TerminateProcess is the Windows equivalent of kill -9: no shutdown hooks, in-flight transactions are cut off
      powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"CommandLine like '%payflow-ledger-%.jar%' and Name='java.exe'\" | ForEach-Object { Invoke-CimMethod -InputObject \$_ -MethodName Terminate | Out-Null }"
    else
      pkill -9 -f 'payflow-ledger-.*\.jar' || true
    fi
    ;;
  *) echo "unknown command $1"; exit 2 ;;
esac
