#!/usr/bin/env bash
# Corrupts one balance as superuser, runs the verifier, waits for the InvariantViolation alert to fire,
# repairs the balance and waits for it to resolve. Prints FIRED then RESOLVED.
set -eu
APP=${APP:-http://localhost:8080}
GRAFANA=${GRAFANA:-http://localhost:3000}
SECRET=${JWT_SECRET:-dev-only-secret-change-me-0123456789abcdef}
PSQL=${PSQL:-docker compose -f deploy/docker-compose.yml exec -T postgres psql -U ledger -d ledger -At}

TOKEN=$(python3 - "$SECRET" <<'PY'
import base64, hmac, hashlib, json, sys, time
b = lambda x: base64.urlsafe_b64encode(x).rstrip(b"=")
h = b(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
p = b(json.dumps({"sub": "smoke", "merchant_id": "admin", "scope": "admin", "exp": int(time.time()) + 600}).encode())
s = b(hmac.new(sys.argv[1].encode(), h + b"." + p, hashlib.sha256).digest())
print((h + b"." + p + b"." + s).decode())
PY
)
verify() { curl -fs -X POST -H "Authorization: Bearer $TOKEN" "$APP/admin/verify" > /dev/null; }
state() { curl -fs "$GRAFANA/api/prometheus/grafana/api/v1/rules" | python3 -c '
import json, sys
for g in json.load(sys.stdin)["data"]["groups"]:
    for r in g["rules"]:
        if r["name"] == "InvariantViolation": print(r["state"])'; }
wait_state() { for _ in $(seq 1 60); do [ "$(state)" = "$1" ] && return 0; verify; sleep 5; done; return 1; }

ID=$($PSQL -c "SELECT id FROM accounts WHERE NOT is_system LIMIT 1")
[ -n "$ID" ] || { echo "no account to corrupt: seed data first"; exit 1; }
$PSQL -c "UPDATE accounts SET balance_minor = balance_minor + 1 WHERE id = '$ID'" > /dev/null
verify
wait_state firing && echo FIRED || { echo "NOT FIRED"; exit 1; }
$PSQL -c "UPDATE accounts SET balance_minor = balance_minor - 1 WHERE id = '$ID'" > /dev/null
verify
wait_state normal && echo RESOLVED || { echo "NOT RESOLVED"; exit 1; }
