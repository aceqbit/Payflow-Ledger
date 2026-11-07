#!/usr/bin/env bash
# Local Kubernetes: kind cluster + CloudNativePG (Postgres 18) + OpenTelemetry Collector chart + the app chart.
# Needs docker, kind, kubectl, helm. NOT exercised in the authoring environment (no Docker there).
set -euo pipefail
cd "$(dirname "$0")/.."
CLUSTER=${CLUSTER:-payflow}

kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER"
mvn -q -DskipTests package jib:dockerBuild
kind load docker-image payflow-ledger:dev --name "$CLUSTER"

# CloudNativePG operator, then a one-instance Postgres 18 cluster named payflow-pg (service payflow-pg-rw)
kubectl apply --server-side -f https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-1.26/releases/cnpg-1.26.0.yaml
kubectl -n cnpg-system rollout status deploy/cnpg-controller-manager --timeout=180s
kubectl apply -f - <<'EOF'
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata: {name: payflow-pg}
spec:
  instances: 1
  imageName: ghcr.io/cloudnative-pg/postgresql:18
  bootstrap:
    initdb: {database: ledger, owner: ledger, secret: {name: payflow-pg-app}}
  storage: {size: 2Gi}
EOF
kubectl get secret payflow-pg-app >/dev/null 2>&1 || kubectl create secret generic payflow-pg-app --type=kubernetes.io/basic-auth \
  --from-literal=username=ledger --from-literal=password=ledger
kubectl wait --for=condition=Ready cluster/payflow-pg --timeout=300s

# OpenTelemetry Collector (OTLP in, debug out; point exporters at your backends as needed)
helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts >/dev/null
helm upgrade --install otel-collector open-telemetry/opentelemetry-collector \
  --set mode=deployment --set image.repository=otel/opentelemetry-collector-contrib

helm upgrade --install payflow deploy/helm/payflow-ledger --wait --timeout 300s
echo "ready: kubectl port-forward svc/payflow-ledger 8080:8080"
