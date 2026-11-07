#!/usr/bin/env bash
# Shared settings. Override any of these in the environment.
export PGHOST=${PGHOST:-localhost}
export PGPORT=${PGPORT:-5432}
export PGUSER=${PGUSER:-ledger}
export PGPASSWORD=${PGPASSWORD:-ledger}
export DB_USER=$PGUSER DB_PASSWORD=$PGPASSWORD
export PROM=${PROM:-http://localhost:9090}
export APP=${APP:-http://localhost:8080}
# Docker-less runs push metrics straight to Prometheus (profile metrics-only). Compose runs set APP_PROFILES="".
export APP_PROFILES=${APP_PROFILES-metrics-only}
PSQL=${PSQL:-psql}
PYTHON=${PYTHON:-python}
mkdir -p perf/results
