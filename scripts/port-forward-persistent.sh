#!/usr/bin/env bash
# Self-healing kubectl port-forwards for every app service and observability tool.
#
# Plain `kubectl port-forward svc/X` pins to whichever pod is behind that Service at the
# moment it starts, and does NOT reconnect when that pod is replaced — a rolling deploy
# (tilt ci, tilt up, kubectl rollout restart) or even a crash-restart kills the tunnel and
# leaves it dead until someone notices and reruns the command by hand. This script wraps
# each port-forward in a restart loop instead: the instant a tunnel dies, for any reason,
# it's reconnected within 2s against whatever pod is currently live. No manual restarts
# needed after a redeploy, ever.
#
# Usage:
#   scripts/port-forward-persistent.sh            # default ports (gateway on :8080, etc.)
#   scripts/port-forward-persistent.sh 10000       # every local port +10000, e.g. :18080 —
#                                                   # run alongside Docker Compose (which owns
#                                                   # the default ports) without a collision;
#                                                   # see docs/local-deployment.html Part 5/6.
#
# Safe to rerun: skips any local port that's already listening rather than stacking a
# duplicate loop on top of one that's already running.
#
# Logs: /tmp/ecommerce-port-forwards/<service>.log — each reconnect is timestamped there.
# Stop everything: pkill -f 'kubectl port-forward'  (the loops notice within 2s and exit
# too, since they're waiting on that same kubectl process).

set -uo pipefail  # not -e: a kubectl failure inside the retry loop must not exit the script

OFFSET="${1:-0}"
LOG_DIR="/tmp/ecommerce-port-forwards"
mkdir -p "$LOG_DIR"

forward_forever() {
  local ns="$1" svc="$2" local_port="$3" remote_port="$4"
  local log="$LOG_DIR/$svc.log"
  while true; do
    echo "[$(date '+%H:%M:%S')] starting port-forward: $ns/$svc $local_port:$remote_port" >>"$log"
    kubectl port-forward -n "$ns" "svc/$svc" "$local_port:$remote_port" >>"$log" 2>&1
    echo "[$(date '+%H:%M:%S')] port-forward for $svc died (exit $?) — reconnecting in 2s" >>"$log"
    sleep 2
  done
}

start_forward() {
  local ns="$1" svc="$2" remote="$3" app_port="$4"
  local local_port=$((app_port + OFFSET))

  if lsof -iTCP:"$local_port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "skip $svc — something is already listening on $local_port"
    return
  fi

  forward_forever "$ns" "$svc" "$local_port" "$remote" &
  disown
  echo "started $svc -> localhost:$local_port (namespace $ns, Service port $remote)"
}

# App services (namespace ecommerce, Helm chart): every Service listens on port 80
# internally regardless of the container's own port — see docs/local-deployment.html
# Part 5/6 for why this differs from the observability tools below.
for svc_port in "gateway-service:8080" "user-service:8081" "product-service:8082" \
                "inventory-service:8083" "order-service:8084" "payment-service:8085" \
                "notification-service:8086"; do
  start_forward ecommerce "${svc_port%%:*}" 80 "${svc_port##*:}"
done

# Observability tools (namespace observability, raw manifests): every Service exposes
# its native port 1:1, no port-80 indirection.
for svc_port in "grafana:3000" "prometheus:9090" "jaeger-query:16686" \
                "kibana:5601" "elasticsearch:9200"; do
  port="${svc_port##*:}"
  start_forward observability "${svc_port%%:*}" "$port" "$port"
done

echo
echo "Logs: $LOG_DIR/<service>.log"
echo "Stop everything: pkill -f 'kubectl port-forward'"
