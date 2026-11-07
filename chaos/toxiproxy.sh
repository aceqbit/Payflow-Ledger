#!/usr/bin/env bash
# Toxiproxy control for the app -> postgres hop.   usage: chaos/toxiproxy.sh start|setup|<latency|reset_peer|timeout>|clear|stop
# Profiles: latency = 50 ms +/- 25 on every packet; reset_peer = 5% of connections reset; timeout = 5% of connections stall 5 s then drop.
set -euo pipefail
TOXI=${TOXI:-http://localhost:8474}
BIN=${TOXIPROXY_SERVER:-$HOME/tools/toxiproxy-server.exe}
[ -x "$BIN" ] || BIN=$(command -v toxiproxy-server)
api() { curl -fs -X "$1" "$TOXI$2" ${3:+-H "Content-Type: application/json" -d "$3"} > /dev/null; }

case "${1:?usage}" in
  start)
    "$BIN" -host 127.0.0.1 -port 8474 > perf/results/toxiproxy.log 2>&1 &
    for _ in $(seq 1 30); do curl -fs "$TOXI/version" > /dev/null 2>&1 && break; sleep 0.5; done ;;
  setup)
    curl -fs -X DELETE "$TOXI/proxies/pg" > /dev/null 2>&1 || true
    api POST /proxies '{"name":"pg","listen":"127.0.0.1:15432","upstream":"127.0.0.1:5432","enabled":true}' ;;
  latency)
    api POST /proxies/pg/toxics '{"name":"chaos","type":"latency","stream":"downstream","toxicity":1.0,"attributes":{"latency":50,"jitter":25}}' ;;
  reset_peer)
    api POST /proxies/pg/toxics '{"name":"chaos","type":"reset_peer","stream":"downstream","toxicity":0.05,"attributes":{"timeout":0}}' ;;
  timeout)
    api POST /proxies/pg/toxics '{"name":"chaos","type":"timeout","stream":"downstream","toxicity":0.05,"attributes":{"timeout":5000}}' ;;
  clear)
    curl -fs -X DELETE "$TOXI/proxies/pg/toxics/chaos" > /dev/null 2>&1 || true ;;
  stop)
    if command -v powershell >/dev/null 2>&1; then
      powershell -NoProfile -Command "Get-Process toxiproxy-server -ErrorAction SilentlyContinue | Stop-Process -Force"
    else
      pkill -f toxiproxy-server || true
    fi ;;
  *) echo "unknown command $1"; exit 2 ;;
esac
