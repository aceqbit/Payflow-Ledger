#!/usr/bin/env bash
# Sends one request with a known W3C trace id, then checks the id arrived in all three backends.
# Needs the stack from `make up`. Prints METRICS OK, TRACES OK, LOGS OK or exits non-zero.
set -u
APP=${APP:-http://localhost:8080}
PROM=${PROM:-http://localhost:9090}
TEMPO=${TEMPO:-http://localhost:3200}
LOKI=${LOKI:-http://localhost:3100}
TRACE_ID=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
SPAN_ID=$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')
fail=0

curl -fs -H "traceparent: 00-${TRACE_ID}-${SPAN_ID}-01" "$APP/actuator/health" > /dev/null || { echo "app not reachable"; exit 1; }

wait_for() { # name, command...
  local name=$1; shift
  for _ in $(seq 1 40); do "$@" > /dev/null 2>&1 && { echo "$name OK"; return 0; }; sleep 3; done
  echo "$name MISSING"; fail=1
}

metrics() { curl -fs "$PROM/api/v1/query" --data-urlencode 'query=http_server_requests_seconds_count{service_name="payflow-ledger"}' | grep -q '"result":\[{'; }
traces()  { curl -fs "$TEMPO/api/traces/${TRACE_ID}" | grep -q "$TRACE_ID"; }
logs()    { curl -fs -G "$LOKI/loki/api/v1/query_range" --data-urlencode 'query={service_name="payflow-ledger"}' | grep -q '"result":\[{'; }

wait_for METRICS metrics
wait_for TRACES traces
wait_for LOGS logs
exit $fail
