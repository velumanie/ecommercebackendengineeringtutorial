# Local Kubernetes dev loop for the e-commerce platform.
#
# Replaces the manual sequence of `./scripts/build-local-images.sh` + several `kubectl apply`s
# + `helm upgrade --install` + N individual `kubectl port-forward`s with one command:
#
#   tilt up
#
# Prerequisites: Docker Desktop's Kubernetes enabled (Settings -> Kubernetes -> Enable),
# context `docker-desktop` current. See docs/local-deployment.html Part 4 for the underlying
# manual steps this automates and the resource-allocation guidance (6+ CPU / 10+ GB RAM,
# more if you bring up observability alongside the app).
#
# Ctrl-C stops the Tilt UI/port-forwards but leaves the cluster running; `tilt down` tears
# down everything Tilt applied.

allow_k8s_contexts('docker-desktop')

# ---- data tier + observability: raw manifests, same ones `docs/local-deployment.html` has
# you `kubectl apply` by hand. ingress.yaml is production-only (real domain, cert-manager) —
# deliberately not included here. ----
k8s_yaml([
    'kubernetes/namespace.yaml',
    'kubernetes/kafka.yaml',
    'kubernetes/redis.yaml',
])
k8s_yaml(listdir('kubernetes/postgres'))
k8s_yaml(listdir('kubernetes/observability'))

# ---- app services: build each image from the same Dockerfiles the manual
# scripts/build-local-images.sh uses, then render the Helm chart with the local-cluster
# values. Tilt intercepts the ecommerce-local/* image refs at apply time and substitutes
# its own freshly-built, content-hash-tagged image — the "local" tag in values-local.yaml
# is just what the chart needs to reference; Tilt doesn't rely on that tag being current. ----
SERVICES = {
    'gateway-service': 8080,
    'user-service': 8081,
    'product-service': 8082,
    'inventory-service': 8083,
    'order-service': 8084,
    'payment-service': 8085,
    'notification-service': 8086,
}

for svc in SERVICES:
    docker_build(
        'ecommerce-local/' + svc,
        context='.',
        dockerfile=svc + '/Dockerfile',
    )

k8s_yaml(helm(
    'helm/ecommerce-platform',
    name='ecommerce',
    namespace='ecommerce',
    values=['helm/ecommerce-platform/values-local.yaml'],
))

# ---- port-forwards + Tilt UI grouping ----
for svc, port in SERVICES.items():
    k8s_resource(svc, port_forwards=port, labels=['app'])

k8s_resource('redis', labels=['app'])

for pg in ['postgres-users', 'postgres-products', 'postgres-inventory',
           'postgres-orders', 'postgres-payments', 'postgres-notifications']:
    k8s_resource(pg, labels=['data'])
k8s_resource('kafka-broker', labels=['data'])

k8s_resource('grafana', port_forwards=3000, labels=['observability'])
k8s_resource('prometheus', port_forwards=9090, labels=['observability'])
k8s_resource('kibana', port_forwards=5601, labels=['observability'])
k8s_resource('jaeger', port_forwards='16686:16686', labels=['observability'])
k8s_resource('elasticsearch', port_forwards=9200, labels=['observability'])
k8s_resource('logstash', labels=['observability'])
k8s_resource('filebeat', labels=['observability'])
k8s_resource('otel-collector', labels=['observability'])
