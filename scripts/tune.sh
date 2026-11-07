#!/usr/bin/env bash
# One tuning measurement: 200 users on uniform and on one hot account.   usage: scripts/tune.sh <step> <before|after> "<note>"
# Extra app arguments via APP_ARGS, e.g. APP_ARGS="--spring.datasource.hikari.maximum-pool-size=32".
set -euo pipefail
cd "$(dirname "$0")/.."
STEP=${1:?step}; PHASE=${2:?before|after}; export RUN_NOTE=${3:-}
for dist in uniform hot; do
  bash scripts/run.sh "tune-$STEP-$PHASE" 200 PESSIMISTIC "$dist" "${DURATION:-120}"
done
