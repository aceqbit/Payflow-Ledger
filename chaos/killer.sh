#!/usr/bin/env bash
# kill -9 the app at random 10-30 s intervals and restart it. Logs each kill and posts a Grafana annotation.
# usage: chaos/killer.sh <kills-log>      (stop it with kill; the log's line count is the kill count)
set -uo pipefail
cd "$(dirname "$0")/.."
LOG=${1:?kills log}
: > "$LOG"
while true; do
  sleep $(( 10 + RANDOM % 21 ))
  bash chaos/app.sh kill
  echo "$(date +%s) killed" >> "$LOG"
  curl -s -m 2 -X POST "${GRAFANA:-http://localhost:3000}/api/annotations" -H 'Content-Type: application/json' \
    -d "{\"tags\":[\"gatling\",\"chaos\",\"kill\"],\"text\":\"kill -9 app ${RUN_ID:-}\"}" > /dev/null 2>&1 || true
  sleep 1
  bash chaos/app.sh start
done
