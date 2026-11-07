#!/usr/bin/env bash
# Docker-less Prometheus: native binary with the OTLP receiver, loading the same rules as the compose stack.
# Needs PROM_HOME pointing at an unpacked prometheus release (default ~/tools/prometheus-*).
set -euo pipefail
cd "$(dirname "$0")/.."
PROM_HOME=${PROM_HOME:-$(ls -d "$HOME"/tools/prometheus-*/ | head -1)}
CFG=deploy/observability/prometheus
mkdir -p perf/results/prom-data
exec "$PROM_HOME/prometheus" --config.file=$CFG/prometheus-native.yml --web.enable-otlp-receiver \
  --enable-feature=exemplar-storage --storage.tsdb.path=perf/results/prom-data \
  --storage.tsdb.retention.time=30d --web.listen-address=:9090
