#!/usr/bin/env bash
# make bench SCENARIO=baseline -> 50/200/500 users uniform + 200 users with telemetry off.
# make bench MATRIX=smoke      -> 2 short runs (the full 27-run matrix lives in bench-matrix.sh, added in P2-03).
set -euo pipefail
cd "$(dirname "$0")/.."
SCENARIO=${SCENARIO:-baseline}
DURATION=${DURATION:-300}
for u in 50 200 500; do
  scripts/run.sh "$SCENARIO" "$u" PESSIMISTIC uniform "$DURATION"
done
scripts/run.sh "$SCENARIO" 200 PESSIMISTIC uniform "$DURATION" telemetry-off
