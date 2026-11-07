#!/usr/bin/env bash
# 3 strategies x {50,200,500} users x {uniform, zipf 80/20, one hot account} = 27 runs, unattended.
# Resumes from the last completed run (state file .bench-state). MATRIX=smoke runs 2 short runs instead.
set -euo pipefail
cd "$(dirname "$0")/.."
STATE=${STATE:-.bench-state}
DURATION=${DURATION:-300}
touch "$STATE"

run() { # strategy users dist duration
  local id="$1-$2-$3-$4"
  if grep -qx "$id" "$STATE"; then echo "skip $id (done)"; return; fi
  bash scripts/run.sh "${SCENARIO:-matrix}" "$2" "$1" "$3" "$4"
  echo "$id" >> "$STATE"
}

if [ "${MATRIX:-}" = "smoke" ]; then
  STATE=.bench-state-smoke; touch "$STATE"
  run PESSIMISTIC 50 uniform 60
  run OPTIMISTIC 50 hot 60
  exit 0
fi

for strategy in PESSIMISTIC OPTIMISTIC SERIALIZABLE; do
  for users in 50 200 500; do
    for dist in uniform zipf hot; do
      run "$strategy" "$users" "$dist" "$DURATION"
    done
  done
done
echo "matrix complete: $(wc -l < "$STATE") runs"
