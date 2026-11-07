#!/usr/bin/env bash
# One transfer through the cluster: seeds two accounts in the cluster Postgres, posts a transfer, checks the response.
# Trace visibility is checked via the Collector's debug exporter logs.
set -euo pipefail
cd "$(dirname "$0")/.."
PSQL_POD=$(kubectl get pod -l cnpg.io/cluster=payflow-pg,role=primary -o jsonpath='{.items[0].metadata.name}')
q() { kubectl exec "$PSQL_POD" -- psql -U postgres -d ledger -At -c "$1"; }

SYS=$(q "INSERT INTO accounts (merchant_id,currency,is_system) VALUES ('kind','USD',true) RETURNING id")
A=$(q "INSERT INTO accounts (merchant_id,currency) VALUES ('kind','USD') RETURNING id")
B=$(q "INSERT INTO accounts (merchant_id,currency) VALUES ('kind','USD') RETURNING id")
q "SELECT 1" > /dev/null
echo "accounts: $A -> $B (seeded without balance: the first transfer must be 409, proving the app reached Postgres)"

kubectl port-forward svc/payflow-ledger 18080:8080 > /dev/null 2>&1 &
PF=$!; trap 'kill $PF 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do curl -fs localhost:18080/actuator/health > /dev/null 2>&1 && break; sleep 1; done
TOKEN=$(python3 scripts/mint_token.py kind)
CODE=$(curl -s -o /tmp/kind-smoke.json -w '%{http_code}' -X POST localhost:18080/v1/transfers \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: kind-smoke-$(date +%s)" -H 'Content-Type: application/json' \
  -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amountMinor\":1,\"currency\":\"USD\"}")
[ "$CODE" = "409" ] && grep -q INSUFFICIENT_FUNDS /tmp/kind-smoke.json && echo "kind smoke OK (409 INSUFFICIENT_FUNDS from the live ledger)" || { echo "unexpected $CODE"; cat /tmp/kind-smoke.json; exit 1; }
