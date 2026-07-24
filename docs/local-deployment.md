# Local Deployment Guide

*Operations Guide*

Two ways to run the whole platform on a laptop — **Docker Compose** (fastest, best for day-to-day development) and a **local single-node Kubernetes cluster** via Docker Desktop (closer to how it actually ships). Both end at the same place: a gateway on `:8080`, a working Postman collection, and dashboards you can actually look at. Pairs with `docs/architecture.html`, which explains *why* things are built this way — this document is just about getting it running.

`docker compose` `Docker Desktop Kubernetes` `Helm` `Postman` `Grafana` `Prometheus` `Jaeger` `Kibana`

## Contents

- [01 Prerequisites](#prereqs)
- [02 Which Path?](#decision)
- [03 Docker Compose](#compose)
- [04 Local Kubernetes](#k8s)
- [05 Service URLs](#urls)
- [06 Observability URLs](#observability)
- [07 Postman Collection](#postman)
- [08 Verification Checklist](#checklist)
- [09 Debugging Observability](#obs-debug)
- [10 Troubleshooting](#troubleshooting)

<a id="prereqs"></a>
## 01 — Prerequisites

| Tool | Needed for | Check |
|---|---|---|
| Docker Desktop | Both paths — provides the Docker engine, and (once enabled) the local Kubernetes cluster | `docker version` |
| Docker Compose | Path A — bundled with Docker Desktop | `docker compose version` |
| kubectl | Path B | `kubectl version --client` |
| Helm 3 | Path B | `helm version` |
| Tilt *(optional)* | Path B — automates the whole manual sequence into `tilt up`, see [Part 4](#k8s) | `tilt version` (`brew install tilt`) |
| Postman (or Newman CLI) | Exercising the API suite — see [Part 7](#postman) | — |

> **Warning:** **Resource allocation matters — more for Kubernetes than Compose.** The full stack is 7 services + 6 Postgres instances + Kafka + Redis, and — if you bring up observability too — Prometheus, Grafana, the OTel Collector, Jaeger, Elasticsearch, Logstash, and Kibana. In Docker Desktop's Settings → Resources, allocate at least **6 CPUs and 10 GB RAM** as a floor before starting either path; if that's not available, skip the observability stack (see the callouts in Parts 3 and 4) and the platform still runs fine — you just lose dashboards/traces/log aggregation.
>
> Kubernetes needs meaningfully more headroom than that floor once you run the full app *and* observability together: K8s schedules on declared resource *requests*, not actual usage, so it fails a pod at admission time (`FailedScheduling: Insufficient memory`) rather than letting it run tight the way Compose does. 10 GB was enough to hit 99% memory-request allocation and start silently OOM-killing pods (no stack trace, just an abrupt exit) with everything running at once; bumping to ~24 GB cleared it. If a pod dies with no application-level error in its logs right around startup, check `kubectl describe node docker-desktop | grep -A5 "Allocated resources"` before debugging application code — it may not be a bug at all.
>
> `helm/ecommerce-platform/values-local.yaml`'s per-service resource requests/limits were raised from an earlier, tighter baseline after `loadtest/peak-load.js` (see the file's own top comment for the specifics) found real request failures under concurrent load that traced to CPU/memory headroom, not bugs — most notably user-service under BCrypt. With the full app + data + observability stack up on this larger request footprint, `kubectl describe node` currently reports ~86% CPU / ~72% memory *requests* allocated on a 10-CPU/24 GB node — the 24 GB recommendation above still holds, just with less slack than it used to; don't go below it now.

<a id="decision"></a>
## 02 — Which Path?

- **Docker Compose:** One command, no cluster concepts, fastest inner loop. Use this day to day. Go to [Part 3](#compose).
- **Local Kubernetes:** Exercises the actual Helm charts and K8s manifests that ship to real clusters — probes, HPAs, ConfigMaps, Secrets, service discovery via CoreDNS. Use this to sanity-check a manifest/chart change before it goes anywhere real. Go to [Part 4](#k8s).

Both land on the exact same API surface (gateway on `localhost:8080`), so the [Postman collection](#postman) and [verification checklist](#checklist) work unmodified against either.

<a id="compose"></a>
## 03 — Path A — Docker Compose

1. **Start everything.**

   ```bash
   docker compose up --build
   ```

   First run builds all 7 images (multi-stage Gradle builds — expect several minutes) and pulls Postgres/Kafka/Redis/observability images. Subsequent runs are fast (layer cache).

2. **Wait for health.** Every service exposes a startup probe equivalent at `/actuator/health`; Flyway migrations (including the [seeded dev admin](#checklist)) run automatically on boot. Watch for each service logging `Started ...Application`:

   ```bash
   docker compose logs -f order-service user-service
   ```

3. **Confirm the gateway is up.**

   ```bash
   curl -s http://localhost:8080/actuator/health | jq .status
   # "UP"
   ```

4. **Run the smoke test.** Import the [Postman collection](#postman) and run *1. Admin Bootstrap* → *2. Customer Journey*, or jump to [Part 8](#checklist) for the equivalent `curl` commands.

5. **Tear down.**

   ```bash
   docker compose down          # stop, keep volumes (data survives restart)
   docker compose down -v       # stop and wipe all data — start completely fresh
   ```

> **Note:** **Lighter footprint:** the observability containers (`prometheus`, `grafana`, `jaeger`, `otel-collector`, `elasticsearch`, `logstash`, `kibana`, `filebeat`) are defined in the same `docker-compose.yaml`. To run just the application: `docker compose up --build gateway-service user-service product-service inventory-service order-service payment-service notification-service postgres-users postgres-products postgres-inventory postgres-orders postgres-payments postgres-notifications kafka redis` — Compose brings up each container's own dependencies automatically, so this list is really just "the app services," not the full dependency graph you have to reason about by hand.

<a id="k8s"></a>
## 04 — Path B — Local Kubernetes (Docker Desktop)

> **Note:** **Prefer one command?** `Tiltfile` at the repo root automates every step below — image builds, namespaces, Postgres/Kafka/Redis, observability, the Helm release, and every port-forward (gateway, all 6 services, Grafana, Prometheus, Kibana, Jaeger, Elasticsearch) — behind `tilt up` (install: `brew install tilt`). It opens a live web UI showing build/deploy/log status per resource and rebuilds automatically on file changes. `tilt down` tears everything back down. `tilt ci` is the same thing as a one-shot: apply, wait for full health, exit — useful for scripting instead of the interactive UI. The manual steps below are what it's automating, worth knowing either way for debugging.
>
> One thing to know if you go looking: Tilt renders the Helm chart itself (like `helm template`) rather than running a real `helm upgrade --install`, so `helm list` won't show an `ecommerce` release — Tilt reconciles the resulting objects through its own engine instead. Doesn't change anything functionally.

1. **Enable Kubernetes.** Docker Desktop → Settings → Kubernetes → check *Enable Kubernetes* → Apply & Restart. Confirm the context:

   ```bash
   kubectl config current-context
   # docker-desktop
   kubectl get nodes
   # NAME             STATUS   ROLES           AGE   VERSION
   # docker-desktop   Ready    control-plane   ...   v1.3x.x
   ```

2. **Build images locally.** Docker Desktop's Kubernetes shares the same image store as its Docker engine, so a local build is immediately usable by the cluster — no registry, no push.

   ```bash
   ./scripts/build-local-images.sh
   ```

   Builds all 7 images as `ecommerce-local/<service>:local`. This is what `helm/ecommerce-platform/values-local.yaml` points at, together with `imagePullPolicy: IfNotPresent` on every Deployment — without that policy, Kubernetes defaults `:latest`-tagged images to `Always` pull, which would fail immediately since `registry.company.com` (the production default) doesn't exist locally.

3. **Create namespaces and stateful dependencies.**

   ```bash
   kubectl apply -f kubernetes/namespace.yaml
   kubectl apply -f kubernetes/postgres/        # 6 StatefulSets, one per service database
   kubectl apply -f kubernetes/kafka.yaml       # 3-broker KRaft StatefulSet — order/inventory/payment/notification-service all need this up before they'll start cleanly
   kubectl apply -f kubernetes/redis.yaml
   kubectl apply -f kubernetes/observability/   # optional — see the resource note in Part 1
   ```

   Wait for Postgres to be ready before the next step (Flyway migrations run on app startup and will crash-loop-retry harmlessly if the DB isn't up yet, but it's faster to just wait):

   ```bash
   kubectl get pods -n data -w
   # ...wait until all postgres-* pods show 1/1 Running
   ```

4. **Deploy the application via Helm.**

   ```bash
   helm upgrade --install ecommerce ./helm/ecommerce-platform \
     -f ./helm/ecommerce-platform/values-local.yaml \
     -n ecommerce --create-namespace
   ```

   ```bash
   kubectl get pods -n ecommerce -w
   # ...wait until every pod is 1/1 Running (startup probes give each ~2.5 min before failing)
   ```

5. **Reach the gateway.** No LoadBalancer or Ingress controller running locally by default, so port-forward:

   ```bash
   kubectl port-forward -n ecommerce svc/gateway-service 8080:80
   ```

   Leave that running in its own terminal. Everything else in this guide — the Postman collection, the `curl` checklist — now works exactly as it does against Compose, because it's the same `localhost:8080`.

   **This breaks on every redeploy** — `kubectl port-forward` pins to whichever pod is behind the Service at the moment it starts, and does not reconnect when that pod is replaced (a rolling update from `tilt ci`/`tilt up`, a crash-restart, anything). The tunnel just dies silently. For anything beyond a single quick check, use `scripts/port-forward-persistent.sh` instead — it wraps every port-forward (gateway, all 6 services, Grafana, Prometheus, Kibana, Jaeger, Elasticsearch) in a restart loop that reconnects within 2s whenever the tunnel drops, indefinitely, with no manual re-running required after a redeploy:

   ```bash
   ./scripts/port-forward-persistent.sh
   ```

   Pass an offset (e.g. `./scripts/port-forward-persistent.sh 10000`) to run every local port at `+10000` instead — gateway on `:18080`, Grafana on `:13000`, etc. — so this stack is reachable *at the same time* as Docker Compose on its normal ports, without either one colliding on the other's port. Logs land in `/tmp/ecommerce-port-forwards/<service>.log`; `pkill -f 'kubectl port-forward'` stops everything the script started.

6. **Tear down.**

   ```bash
   helm uninstall ecommerce -n ecommerce
   kubectl delete -f kubernetes/observability/ -f kubernetes/redis.yaml -f kubernetes/postgres/ -f kubernetes/namespace.yaml
   ```

> **Warning:** **Common first-run failure:** pods stuck in `ImagePullBackOff`. This means `values-local.yaml` wasn't passed to `helm upgrade` (so it's still trying to pull `registry.company.com/ecommerce/...`), or `build-local-images.sh` wasn't run first. `kubectl describe pod <pod> -n ecommerce` shows exactly which image it's trying to pull — compare that against `docker images | grep ecommerce-local`.

<a id="urls"></a>
## 05 — Service URLs

Identical for both paths once the Kubernetes gateway is port-forwarded ([Part 4, step 5](#k8s)) — everything routes through the gateway on `:8080` in normal use. Direct per-service ports are for debugging: "is this the gateway's fault or the service's?"

### Through the gateway (what you'll actually use)

| Route | URL | Backing service |
|---|---|---|
| Auth | [http://localhost:8080/users/auth/login](http://localhost:8080/users/auth/login) | user-service |
| Users | [http://localhost:8080/users](http://localhost:8080/users) | user-service |
| Products | [http://localhost:8080/products](http://localhost:8080/products) | product-service |
| Inventory | [http://localhost:8080/inventory](http://localhost:8080/inventory) | inventory-service |
| Orders | [http://localhost:8080/orders](http://localhost:8080/orders) | order-service |
| Payments | [http://localhost:8080/payments](http://localhost:8080/payments) | payment-service |
| Notifications | [http://localhost:8080/notifications](http://localhost:8080/notifications) | notification-service |
| Gateway health | [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health) | gateway-service |

### Direct per-service (Compose: always on. Kubernetes: needs its own `port-forward`)

| Service | URL | K8s port-forward |
|---|---|---|
| gateway-service | [http://localhost:8080](http://localhost:8080) | `svc/gateway-service 8080:80` |
| user-service | [http://localhost:8081](http://localhost:8081) | `svc/user-service 8081:80` |
| product-service | [http://localhost:8082](http://localhost:8082) | `svc/product-service 8082:80` |
| inventory-service | [http://localhost:8083](http://localhost:8083) | `svc/inventory-service 8083:80` |
| order-service | [http://localhost:8084](http://localhost:8084) | `svc/order-service 8084:80` |
| payment-service | [http://localhost:8085](http://localhost:8085) | `svc/payment-service 8085:80` |
| notification-service | [http://localhost:8086](http://localhost:8086) | `svc/notification-service 8086:80` |

Kubernetes port-forward pattern: `kubectl port-forward -n ecommerce svc/<name> <local-port>:80` (every Service listens on port 80 internally regardless of the container's own port — see each chart's `service.yaml`). This is the opposite convention from the observability/infra Services in [Part 6](#observability) — see the note there for why, and how to check any Service's real port yourself instead of guessing. For anything beyond a single quick command, run `./scripts/port-forward-persistent.sh` (see [Part 4, step 5](#k8s)) instead of one-off `kubectl port-forward` calls — plain ones silently die on every redeploy.

**No Swagger UI right now.** springdoc-openapi 2.8.17 (the latest release) hasn't caught up to Spring Boot 4.1's package layout and crashes every service that carries it, so it's been removed for the moment — see `docs/architecture.html` Part 3. Use the [Postman collection](#postman) or each service's controller source to see the actual API surface until an updated springdoc release lands.

### Infrastructure

| Component | Compose | Kubernetes port-forward |
|---|---|---|
| Kafka | `localhost:9092` | not exposed by default — exec into a pod or add a port-forward to your Kafka Service/StatefulSet |
| Redis | `localhost:6379` | `svc/redis 6379:6379` (namespace `ecommerce`) |
| postgres-orders (and the other 5) | `localhost:5444` (see `docker-compose.yaml` for the other 5 ports) | `svc/postgres-orders 5432:5432` (namespace `data`, headless — port-forward still works) |

<a id="observability"></a>
## 06 — Observability URLs

Same story as service URLs — Compose exposes these directly; Kubernetes needs one `port-forward` per tool (namespace `observability`). Every command below is copy-pasteable as-is — the port-forward already matches what each URL expects. `./scripts/port-forward-persistent.sh` ([Part 4, step 5](#k8s)) starts all five of these plus every app service in one shot, and — unlike the individual commands below — keeps reconnecting automatically through redeploys instead of silently dying.

| Tool | What it's for | Compose URL | Kubernetes |
|---|---|---|---|
| Grafana | Dashboards — all 7 services pre-provisioned, no login setup needed | [http://localhost:3000](http://localhost:3000) | `kubectl port-forward -n observability svc/grafana 3000:3000` → [http://localhost:3000](http://localhost:3000) |
| Prometheus | Raw metrics + targets page (`/targets` — confirm every service shows UP) | [http://localhost:9090](http://localhost:9090) | `kubectl port-forward -n observability svc/prometheus 9090:9090` → [http://localhost:9090](http://localhost:9090) |
| Jaeger UI | Distributed traces — search by service name, e.g. `order-service` | [http://localhost:16686](http://localhost:16686) | `kubectl port-forward -n observability svc/jaeger-query 16686:16686` → [http://localhost:16686](http://localhost:16686) |
| Kibana | Log search — Discover tab, filter on `correlationId` | [http://localhost:5601](http://localhost:5601) | `kubectl port-forward -n observability svc/kibana 5601:5601` → [http://localhost:5601](http://localhost:5601) |
| Elasticsearch | Raw index API — usually only needed to sanity-check an index exists (`curl localhost:9200/_cat/indices`) before troubleshooting Kibana | [http://localhost:9200](http://localhost:9200) | `kubectl port-forward -n observability svc/elasticsearch 9200:9200` → [http://localhost:9200](http://localhost:9200) |

> **Note:** **How the port in each URL is actually decided — read this before adding a tool or wondering why a port-forward "doesn't match."** It's tempting to assume the URL's port is just "whatever port the tool normally uses" — that happens to be true for every row in this table, but only by convention, not by rule, and this repo's own app services ([Part 5](#urls)) intentionally break that convention. The actual rule:
>
> - **Docker Compose:** the URL's port is the *host* side of that container's `ports:` mapping in `docker-compose.yaml` (`"3000:3000"` for Grafana, etc.) — the left-hand number, not necessarily the container's internal port, though here they happen to be identical.
> - **Kubernetes:** `kubectl port-forward svc/<name> <local>:<remote>` — `<remote>` must match that **Service's own `spec.ports[].port` field**, not the container's `containerPort`/the app's actual listening port, and not the Service's `targetPort` either if the two diverge. When in doubt, don't guess — ask the Service directly: `kubectl get svc -n observability grafana -o jsonpath='{.spec.ports[0].port}'`.
>
> Why this matters here specifically: **every tool in this table uses `port: targetPort`, so its Service port and the app's real port are the same number** — that's why `3000:3000`, `9090:9090`, etc. all look redundant. The app services in [Part 5](#urls) do *not* follow that pattern — their Helm chart deliberately gives every Service `port: 80` (a Kubernetes/Helm convention: "a Service always answers on the standard HTTP port") regardless of which port the container inside actually listens on, so `gateway-service`'s port-forward is `8080:80`, not `8080:8080`. Two different, both internally consistent conventions, applied to two different categories of manifest in this same repo (raw YAML for observability/infra, a templated Helm chart for the app) — the failure mode if you assume one applies to the other is a port-forward that starts up looking fine and then every request just hangs or connection-refuses, because it's forwarding to a port nothing is actually listening on.

> **Note:** **Kibana needs a one-time data view before Discover shows anything.** Unlike Grafana's dashboards, nothing pre-provisions this. Create it once per fresh Elasticsearch volume:
>
> ```bash
> curl -X POST "http://localhost:5601/api/data_views/data_view" \
>   -H "kbn-xsrf: true" -H "Content-Type: application/json" \
>   -d '{"data_view": {"title": "ecommerce-*", "name": "E-Commerce Platform Logs", "timeFieldName": "@timestamp"}}'
> ```
>
> (Or in the UI: Kibana → Stack Management → Data Views → Create data view, index pattern `ecommerce-*`, time field `@timestamp`.)

Grafana ships with anonymous viewer access enabled locally (see `docker-compose.yaml` / `kubernetes/observability/grafana.yaml`) — no credentials needed for either path.

> **Note:** **Load testing shows up here too.** Prometheus's remote-write receiver is enabled specifically so [a k6 run](../loadtest/README.md) can push its metrics straight in, alongside the app's own golden-signal dashboards — *E-Commerce Platform → Load Testing (k6)*. For real numbers without running anything yourself, see [docs/performance-baseline.html](performance-baseline.md), a dated snapshot comparing Compose against Kubernetes.

<a id="postman"></a>
## 07 — Postman Collection

Verified end to end via Newman against a real `docker compose up --build` stack: all 36 requests / 49 assertions across all 6 folders pass on a clean run.

Lives in `postman/`:

```text
postman/
├── ecommerce-platform.postman_collection.json
├── local-docker-compose.postman_environment.json
└── local-kubernetes.postman_environment.json
```

1. **Import.** Postman → *Import* → drag in all three files from `postman/`.
2. **Select an environment.** Top-right dropdown → *Local - Docker Compose* or *Local - Kubernetes (port-forward)*. Both point at `http://localhost:8080` by default — the split exists so you can tell at a glance which stack a run was against, and so you have somewhere to change the URL if you switch to Ingress-based access later.
3. **Run the collection.** Folders are numbered in run order:

   | Folder | Runs as | Does |
   |---|---|---|
   | 1. Admin Bootstrap | seeded dev admin | Login, refresh the access token, create a product, stock it, create a real customer account |
   | 2. Customer Journey | the customer just created | Login, place an order, look it up, list "my orders", cancel it, log out, prove the revoked refresh token no longer works |
   | 3. Order Lifecycle | admin | Advance order status, attempt an invalid status jump (409), list all orders, confirm the email-notification event landed |
   | 4. Negative / RBAC Cases | mixed | Proves the security model — 403 for wrong role, 401 for no/invalid credentials, 409 for a bad reference or insufficient stock, 404 for a nonexistent order |
   | 5. Validation & Pagination Edge Cases | admin / public | 400 for a missing required field, 200-with-empty-content for a page past the end of the data |
   | 6. Direct Service Health | — | One `/actuator/health` hit per service, bypassing the gateway |

4. **Use Collection Runner for a one-click full pass:** select the collection → *Run* → run all 6 folders top to bottom. Each request's *Tests* script feeds the next one (captures `accessToken`, `productId`, `customerId`, `orderId` into collection variables) — that's why order matters.

> **Note:** **Prefer the command line?** The same collection runs headless via [Newman](https://www.npmjs.com/package/newman):
>
> ```bash
> npx newman run postman/ecommerce-platform.postman_collection.json \
>   -e postman/local-docker-compose.postman_environment.json
> ```

<a id="checklist"></a>
## 08 — Verification Checklist

Component-by-component, in dependency order. The `curl` commands are the manual equivalent of running the Postman collection — use them if you want to see exactly what's happening at each step, or don't have Postman handy. All assume the gateway is reachable at `localhost:8080` ([Part 5](#urls)).

### 1 — Infrastructure is up

```bash
# Compose
docker compose ps --format "table {{.Name}}\t{{.Status}}"

# Kubernetes
kubectl get pods -n data -n ecommerce -n observability
```

Every pod/container should be Running/healthy. A service stuck restarting almost always means its database isn't ready yet or Flyway failed — check that service's logs first.

### 2 — Gateway routes and JWT issuance work

```bash
curl -s http://localhost:8080/actuator/health
# {"status":"UP"}

curl -s -X POST http://localhost:8080/users/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Admin@12345"}' | jq .
# {"accessToken": "...", "refreshToken": "...", "expiresInSeconds": 900}
```

That login only works because `user-service`'s `V1__init_users.sql` Flyway migration seeded a dev admin — this is the one piece of seed data every other check in this list depends on (see the note at the bottom of `user-service/src/main/resources/db/migration/V1__init_users.sql`).

### 3 — Catalog, inventory, and the order flow end to end

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/users/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Admin@12345"}' | jq -r .accessToken)

PRODUCT_ID=$(curl -s -X POST http://localhost:8080/products \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-CLI-001","name":"CLI Test Product","price":19.99}' | jq -r .id)

curl -s -X POST http://localhost:8080/inventory/$PRODUCT_ID/stock \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"quantityOnHand": 25}'

CUSTOMER=$(curl -s -X POST http://localhost:8080/users \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"email":"cli-customer@example.com","password":"Customer@12345","firstName":"CLI","lastName":"Tester","roles":["CUSTOMER"]}')
CUSTOMER_ID=$(echo $CUSTOMER | jq -r .id)

CUSTOMER_TOKEN=$(curl -s -X POST http://localhost:8080/users/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"cli-customer@example.com","password":"Customer@12345"}' | jq -r .accessToken)

curl -s -X POST http://localhost:8080/orders \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"customerId\":\"$CUSTOMER_ID\",\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":1}]}" | jq .
# "status": "CONFIRMED"  <-  inventory reserved + payment authorized synchronously
```

A `CONFIRMED` order here means: product-service, inventory-service, order-service, and payment-service all successfully talked to each other (order-service → inventory-service and order-service → payment-service, both over OpenFeign) in a single request.

### 4 — Kafka + the async notification chain

```bash
curl -s "http://localhost:8080/notifications/emails?page=0&size=5" \
  -H "Authorization: Bearer $TOKEN" | jq '.content[0]'
```

A non-empty result confirms the full event chain: payment-service published `payment.completed` to Kafka → notification-service consumed it → wrote a row to `email_logs`. If this is empty but step 3 returned `CONFIRMED`, check Kafka connectivity and notification-service's consumer logs specifically — the order itself already succeeded, so the break is isolated to the async leg.

### 5 — Metrics, traces, and logs are actually flowing

| Check | Where | Looking for |
|---|---|---|
| Prometheus is scraping every service | [Prometheus → Status → Targets](http://localhost:9090/targets) | All `ecommerce-services`/`kubernetes-pods` targets UP |
| Grafana dashboards have data | [Grafana](http://localhost:3000) → *E-Commerce Platform* folder | Request-rate panels showing the traffic from step 3 (may take up to 15s — the scrape interval) |
| Traces for the order request exist | [Jaeger](http://localhost:16686) → Service `order-service` → Find Traces | A trace spanning gateway → order-service → inventory-service/payment-service |
| Structured logs are searchable | [Kibana](http://localhost:5601) → Discover | JSON log lines with a `correlationId` field; filter on the order's correlation ID (from the response headers of any request in step 3) to see its full cross-service log trail |

### 6 — Security model actually enforces what it claims

```bash
# CUSTOMER hitting an ADMIN-only endpoint -> 403
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/users \
  -H "Authorization: Bearer $CUSTOMER_TOKEN"
# 403

# No token at all -> 401
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/orders
# 401
```

See [Part 5](#urls) for what "401 vs 403" actually means here — it's not interchangeable, and both codes being distinct and correct is itself something worth checking (this platform migrated from a hand-rolled JWT filter to Spring Security's OAuth2 Resource Server specifically to get this right).

### 7 — Idempotency, outbox, and brute-force protection actually work

None of these show up in a happy-path smoke test — they only matter under retry/replay/attack conditions, so they're worth checking explicitly rather than assuming from the code that they're wired correctly.

```bash
# Same Idempotency-Key twice -> 201 then 200, identical order id, inventory reserved once
KEY="verify-$(date +%s)"
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/orders \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "{\"customerId\":\"$CUSTOMER_ID\",\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":1}]}"
# 201
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/orders \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "{\"customerId\":\"$CUSTOMER_ID\",\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":1}]}"
# 200 — same order, not a second one

# Outbox rows actually got published (run against the service's own Postgres container)
docker exec ecommerce-platform-postgres-orders-1 psql -U orders_user -d orders_db \
  -c "select event_type, published_at is not null as published from outbox_events order by created_at desc limit 3;"
# every row should show published = t within ~500ms of being written (the poller's fixedDelay)

# Six failed logins against the same account -> 423 on the sixth (five is the threshold)
for i in 1 2 3 4 5 6; do
  curl -s -o /dev/null -w "%{http_code} " -X POST http://localhost:8080/users/auth/login \
    -H 'Content-Type: application/json' -d '{"email":"admin@example.com","password":"wrong"}'
done; echo
# 401 401 401 401 401 423

# Security response headers present on every gateway response
curl -sI http://localhost:8080/actuator/health | grep -iE "content-security-policy|referrer-policy|permissions-policy"
```

The lockout resets after 15 minutes or on the next successful login (see `User.recordSuccessfulLogin()`) — if you actually trip it while testing, either wait it out or reset it directly: `UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE email = 'admin@example.com';` against `postgres-users-1`.

<a id="obs-debug"></a>
## 09 — Debugging Observability

The observability stack is its own integration surface — a service can be perfectly healthy while its metrics, logs, or traces silently never arrive anywhere. Each pillar below has an actual place to check, a specific thing to look for, and the exact command to look with. All three pillars were broken at some point during this platform's own development for exactly the reasons listed here — this isn't hypothetical.

### Metrics not showing up (Prometheus / Grafana)

1. **Is Prometheus actually scraping the service?** Check the targets API, not just the UI's */targets* page (same data, easier to script):

   ```bash
   curl -s http://localhost:9090/api/v1/targets | jq -r \
     '.data.activeTargets[] | "\(.labels.instance) -> \(.health) \(.lastError)"'
   ```

   `health: "down"` with `401` in the error means the target's `/actuator/prometheus` endpoint is rejecting Prometheus's (credential-less) scrape request — check that service's `SecurityConfig` has `/actuator/prometheus` in its `permitAll()` list alongside `/actuator/health/**`. This bit every service except gateway-service at one point (gateway has no Spring Security dependency at all, so it was never affected).

2. **Does the metric actually have data, not just a healthy target?** A target can be `up` while a specific metric never populates:

   ```bash
   curl -s -G http://localhost:9090/api/v1/query \
     --data-urlencode 'query=jvm_memory_used_bytes{instance="order-service:8084"}' | jq
   ```

   Empty `result` array with a healthy target usually means the metric name or label doesn't exist yet (nothing's triggered it) rather than a pipeline break — try a metric that's always present, like `process_uptime_seconds`, to isolate "no data ever" from "no data for this specific metric."

3. **Metrics-over-OTLP specifically (not the Prometheus scrape path) never arriving, with `Failed to publish metrics to OTLP receiver (...url=http://localhost:4318...)` in a service's own logs:** Boot 4.1 splits OpenTelemetry export into two independent properties — `management.opentelemetry.tracing.export.otlp.endpoint` for traces and `management.otlp.metrics.export.url` for metrics. They don't share one setting; fixing one and assuming the other follows is exactly how this shipped broken initially. Both need the same profile-scoped override (`otel-collector` for docker, `otel-collector.observability.svc.cluster.local` for kubernetes) in every service's `application.yml`.

4. **Is Grafana's datasource actually wired to Prometheus?**

   ```bash
   # Compose (anonymous):
   curl -s http://localhost:3000/api/datasources | jq

   # K8s (needs the admin password from the Secret):
   kubectl get secret grafana-secret -n observability -o jsonpath='{.data.admin-password}' | base64 -d
   curl -s http://localhost:3000/api/datasources -u admin:<password-from-above> | jq
   ```

   Should return one Prometheus datasource with `url: "http://prometheus:9090"`. A dashboard showing "No data" with a working datasource almost always means the panel's PromQL `application="..."` label doesn't match what that service actually reports — check via step 2 above with the panel's exact query.

### Logs not showing up (Kibana / Logstash / Filebeat / Elasticsearch)

1. **Does Elasticsearch have the index at all?**

   ```bash
   curl -s "http://localhost:9200/_cat/indices/ecommerce-*?v"
   ```

   No rows at all (not even old/stale ones) means nothing has ever successfully indexed — go to step 2. Indices exist but stopped growing recently is a different problem (check Filebeat's own harvester state instead, see step 3).

2. **No indices — check Logstash's own logs for why it's rejecting or failing to write:**

   ```bash
   docker compose logs logstash --tail 50        # Compose
   kubectl logs -n observability deploy/logstash --tail 50   # K8s
   ```

   - `Elasticsearch Unreachable` / `ResolutionFailure` — wrong hostname in `output.elasticsearch.hosts`. The Compose pipeline (`monitoring/logstash/pipeline.conf`) must use the bare Compose service name `elasticsearch`; the Kubernetes one (`kubernetes/observability/logstash.yaml`'s embedded config) uses the real `elasticsearch.observability.svc.cluster.local` FQDN. These are two separate config files with two separate correct answers — copying one into the other's context is the actual bug that shipped here once.
   - `Badly formatted index, after interpolation still contains placeholder` — the index-name pattern references a field that doesn't exist on the log event. There is no plain `service` field on these logs. The real per-container/per-pod identifier is `docker.container.labels.com_docker_compose_service` under Compose (from Filebeat's Docker autodiscover) and `kubernetes.labels.app` under Kubernetes (from Filebeat's `add_kubernetes_metadata` processor) — genuinely different fields per environment, both wired into the two pipeline configs already.

3. **Is Filebeat actually harvesting the container logs?**

   ```bash
   docker compose logs filebeat --tail 50 | grep -i "harvester\|error"
   kubectl logs -n observability -l app=filebeat --tail 50 | grep -i "harvester\|error"
   ```

   Look for `Harvester started` lines per container and any `Failed to connect to backoff(...logstash:5044)` errors — the latter is usually just Logstash not being up yet when Filebeat first tries (self-heals with backoff/retry), not a real problem unless it never recovers.

4. **Indexing is fine but Kibana Discover shows nothing:** no data view exists — see the callout in [Part 5](#urls). This has to be created once per fresh Elasticsearch volume/deployment (Compose and K8s each have their own Elasticsearch, so this is per-environment, not global).

### Traces not showing up (Jaeger / OTel Collector)

1. **Is the OTel Collector actually running, not crash-looping?**

   ```bash
   docker compose ps otel-collector
   kubectl get pods -n observability -l app=otel-collector
   ```

   The telltale symptom of a broken collector image (this happened with a specific bad upstream tag, `otel/opentelemetry-collector-contrib:0.116.0`, on arm64) is `exec /otelcol-contrib: no such file or directory` in its logs immediately on start, with no further output. If you ever bump the collector's version and hit this again: it's the image, not your config — try the adjacent patch version (it worked going from `0.116.0` to `0.117.0` with zero config changes) before debugging anything else. Both `docker-compose.yaml` and `kubernetes/observability/otel-collector.yaml` pin the version independently — a version bump has to happen in both places.

2. **Is a service actually sending spans?** Check that service's own logs for `Failed to export spans` — usually `UnknownHostException: otel-collector`, which just means the observability stack isn't running (harmless, the app keeps working) rather than a real misconfiguration, *unless* the collector is confirmed up per step 1, in which case check the service's `management.opentelemetry.tracing.export.otlp.endpoint` for the same profile-scoping mistake described in the Metrics section above.

3. **Search Jaeger directly instead of guessing from the UI:**

   ```bash
   curl -s http://localhost:16686/api/services | jq
   curl -s "http://localhost:16686/api/traces?service=order-service&limit=5" | jq '.data | length'
   ```

   An empty services list means no traces have arrived at all (collector/export problem, see steps above); a populated services list but no traces for one specific service means that service isn't generating the traffic to trace yet — hit one of its endpoints and check again.

<a id="troubleshooting"></a>
## 10 — Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Login returns `401` even with the seeded credentials | `user-service`'s Flyway migrations didn't run (fresh volume + slow Postgres startup race), or you're hitting the wrong port | Check `docker compose logs user-service` / `kubectl logs -n ecommerce deploy/user-service` for `Migrating schema` lines; confirm you're on `:8080` (gateway), not a stale port-forward |
| Order creation returns `409 inventory-unavailable` | No stock row exists for that product yet | Run the "Stock the product" request (Postman) or the `POST /inventory/{id}/stock` call (step 3 of the checklist) before ordering |
| K8s pods stuck `ImagePullBackOff` | Deployed without `values-local.yaml`, or images weren't built locally first | See the callout in [Part 4](#k8s); re-run `helm upgrade` with `-f values-local.yaml` |
| K8s pods `Pending` forever | Not enough CPU/memory allocated to Docker Desktop, or PVCs can't bind | `kubectl describe pod <pod> -n ecommerce` shows the scheduler's reason; increase Docker Desktop's resource allocation ([Part 1](#prereqs)) or skip `kubernetes/observability/` to free up headroom |
| Postgres StatefulSets never go `Running` | PVC stuck `Pending` | `kubectl get pvc -n data` — Docker Desktop ships a default `hostpath` StorageClass, so this is usually a resource-pressure issue, not a missing StorageClass |
| Prometheus target shows `DOWN` for a service | Missing/mismatched `prometheus.io/*` pod annotations, or the service crashed after being scraped once | `kubectl get pod <pod> -n ecommerce -o yaml \| grep prometheus.io` — every Deployment should carry `scrape: "true"`, the right `port`, and `path: /actuator/prometheus` |
| Everything 500s with no clear pattern | Shared `JWT_SECRET` mismatch between services (each reads it from its own Secret/ConfigMap) | Every service must resolve the *same* `JWT_SECRET` value — in Helm, override it once via `--set-string` or a shared values file rather than editing each subchart's default independently |
| Login/token issuance throws `WeakKeyException` or `SignatureException` about HS512 key size | A custom `JWT_SECRET` override is shorter than 64 bytes — HS512 requires a key `>=` 512 bits (RFC 7518 §3.2), and jjwt enforces it strictly | Use a 64+ character secret. The shipped defaults in `docker-compose.yaml` and each service's `application.yml` already satisfy this — this only bites if you supply your own, shorter value |
| order-service returns `500` instead of the expected `409`/`402` for a bad reference or declined payment | A Feign client's `fallbackFactory` silently isn't being invoked — `spring.cloud.openfeign.circuitbreaker.enabled` (not the older `feign.circuitbreaker.enabled`) must be `true`, and a `CircuitBreakerFactory` bean must exist | Check order-service logs for the raw `FeignException` reaching `GlobalExceptionHandler`'s generic handler instead of a mapped domain exception; see `docs/architecture.html` Part 18 for the exact property key and the Spring Cloud 2025.1.2 dependency exclusion this needs |
| Kafka consumer never picks up events | Consumer group offset stuck from a previous run with different message shapes | `docker compose down -v` (Compose) or delete/re-create the Kafka pod's PVC (K8s) for a clean slate |
| Kibana Discover shows nothing (or errors with no data view) | Either no data view exists yet, or a Logstash pipeline misconfiguration | See [Part 9](#obs-debug) for the full "logs not showing up" walkthrough |
| K8s: gateway-service crash-loops on startup with `Failed to bind properties under 'spring.data.redis.port' to int` / a `NumberFormatException` mentioning `tcp://` | Kubernetes auto-injects a `REDIS_PORT` env var (Docker-links format, e.g. `tcp://10.x.x.x:6379`) into every pod in the same namespace as a Service literally named `redis` — it collides with gateway-service's own `${REDIS_PORT:6379}` placeholder, which picks up the colliding value instead of a plain port number | Already fixed by pinning `spring.data.redis.port: 6379` explicitly in gateway-service's `kubernetes` profile instead of relying on the env-var placeholder — if you rename the Redis Service or add a new one, re-check for this same collision pattern on any app-level env var that matches `<SERVICE_NAME>_PORT`/`_SERVICE_HOST` |
| K8s: `kafka-broker-0` stuck `CrashLoopBackOff` and never joined by `kafka-broker-1`/`-2` | StatefulSet deadlock — default `OrderedReady` pod management creates pod 1 only after pod 0 is `Ready`, but pod 0 can never become `Ready` without 2 peers to form Raft quorum with; separately, the headless Service must publish DNS for not-yet-ready pods or the brokers can't resolve each other during that same bootstrap race | Already fixed in `kubernetes/kafka.yaml` via `podManagementPolicy: Parallel` (StatefulSet) and `publishNotReadyAddresses: true` (Service) — if this recurs after editing that file, check both settings survived the edit; `kubectl logs -n data kafka-broker-0` showing `UnknownHostException: kafka-broker-1...` confirms it's the DNS half specifically |
| gateway-service fails to start with `APPLICATION FAILED TO START` / `required a single bean, but 2 were found: userKeyResolver, ipKeyResolver` | Spring Cloud Gateway's `RequestRateLimiterGatewayFilterFactory` autowires one default `KeyResolver` bean (used when a route doesn't name one via `key-resolver` SpEL) — adding a second `KeyResolver` bean without `@Primary` makes that autowiring ambiguous and fails `ApplicationContext` startup outright, even though every route in `application.yml` does specify its resolver explicitly | Already fixed: `userKeyResolver` in `gateway-service/config/RateLimiterConfig.java` is marked `@Primary` — if you add a third `KeyResolver` (another route-specific one), it doesn't need `@Primary` as long as exactly one bean overall carries it |
| Newman's "At least one email was logged" assertion fails on the very first run after `docker compose down -v && up -d --build` | Cold-start race between the outbox poller's first tick, Kafka consumer group formation, and notification-service's own startup — nothing is actually broken, the async chain (outbox → Kafka → consumer) just hasn't caught up yet on a stack that's seconds old | Re-run the collection; this has never failed twice in a row in this project's own testing. If it does fail twice, that's a real regression — check notification-service logs for consumer errors, not a flake |
| A K8s port-forward that was working suddenly connection-refuses, with no error printed anywhere obvious | `kubectl port-forward` pins to one specific pod at start time and never reconnects when that pod is replaced — any redeploy (`tilt ci`, `tilt up` after a code change, `kubectl rollout restart`) or even just a crash-restart kills the tunnel silently. This is normal `kubectl` behavior, not a bug in this platform | Use [`scripts/port-forward-persistent.sh`](#k8s) instead of a bare `kubectl port-forward` — it auto-reconnects within 2s of any drop, indefinitely. If you're already using it and a tunnel still looks dead, check `/tmp/ecommerce-port-forwards/<service>.log` for repeated immediate failures (that points at a real problem — e.g. the Service itself is gone) rather than the normal one-line "died, reconnecting" you'd see for a single pod replacement |

For anything not covered here: [Part 9](#obs-debug) covers observability-pipeline issues specifically; `docs/architecture.html` Part 20 has the broader production-readiness checklist, and Part 9/10 there explain the observability/logging design this guide's checks are built around.

---

Local deployment reference for the six-service e-commerce platform. See `docs/architecture.html` for the design rationale behind every choice this guide walks you through running.
