#!/usr/bin/env bash
# Builds all 7 service images tagged for local Kubernetes use (Docker Desktop's
# built-in cluster, kind, or minikube --driver=docker all share the host's Docker
# image cache, so no registry push is needed — see docs/local-deployment.html).
set -euo pipefail
cd "$(dirname "$0")/.."

REGISTRY="ecommerce-local"
TAG="local"
SERVICES="gateway-service user-service product-service inventory-service order-service payment-service notification-service"

for svc in $SERVICES; do
  echo "==> Building ${REGISTRY}/${svc}:${TAG}"
  docker build -t "${REGISTRY}/${svc}:${TAG}" -f "${svc}/Dockerfile" .
done

echo
echo "Done. Images built:"
docker images --filter "reference=${REGISTRY}/*" --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}"
