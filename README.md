# E-Commerce Microservices Platform

Six bounded-context services — **User**, **Product**, **Inventory**, **Order**, **Payment**, **Notification** — behind a Spring Cloud Gateway, discovered via Kubernetes-native DNS, integrated with OpenFeign (sync) and Kafka (async). Java 25, Spring Boot 4.1, PostgreSQL (database-per-service), Kubernetes/Helm, Prometheus/Grafana/OpenTelemetry, ELK.

**Full architecture, database design, API contracts, security model, observability, and operations reference:** [`docs/architecture.html`](docs/architecture.html).
**Step-by-step local deployment (Docker Compose or a local Kubernetes cluster), service/observability URLs, and a runnable Postman collection:** [`docs/local-deployment.html`](docs/local-deployment.html).
**New to this stack? Start with** [`docs/learning-guide.html`](docs/learning-guide.html) — a guided path through the same technologies, junior- and lead-level takeaways at every stop.
**Want to build it yourself, from nothing?** [`docs/tutorial.html`](docs/tutorial.html) — a phased, from-scratch walkthrough with real code and config at every step, all 11 phases complete: one service standalone through security, Kafka, hardening, containers, Kubernetes, observability, and a real load test.

## What's implemented

`order-service` is the complete reference implementation — every layer (controller, service, repository, entity, dto, mapper, config, security, exception, event, integration), Flyway migrations, JUnit 5 + Mockito unit tests, `@WebMvcTest` controller tests, a Testcontainers integration test, a Dockerfile, Kubernetes manifests, and a Helm chart. Read it first; the other five services follow the identical structure.

`user-service` (JWT issuance/refresh), `product-service`, `inventory-service`, `payment-service`, and `notification-service` follow the same full pattern — working CRUD/business logic, unit + controller + Testcontainers tests, Flyway DDL, Dockerfiles, and Kubernetes manifests. `gateway-service` (Spring Cloud Gateway, WebFlux) has its own unit + WebFlux-slice test suite for its global filters and rate-limiter config.

**Observability is fully wired, not just documented**: every service annotates its pods for Prometheus scraping, exports traces via `spring-boot-starter-opentelemetry`, and ships structured JSON logs. `kubernetes/observability/` has real manifests to run Prometheus, Grafana (with all 7 per-service dashboards pre-provisioned), the OTel Collector, Jaeger, and the ELK stack — not just their config files. The same stack runs locally via `docker-compose.yaml`. Every service also has its own CI/CD workflow under `.github/workflows/`.

**Golden-path choices, applied uniformly across services:**
- **AuthN**: Spring Security's OAuth2 Resource Server (`NimbusJwtDecoder` + `JwtAuthenticationConverter`) validates the JWT on every service — not a hand-rolled filter — so an invalid/expired token correctly yields `401` and a role mismatch yields `403`.
- **Errors**: every `@ExceptionHandler` returns Spring's native `ProblemDetail` (RFC 9457) — no custom error DTO.
- **Logging**: `logging.structured.format.console: logstash` — Boot's built-in structured logging, no `logback-spring.xml` or external encoder per service.
- **Concurrency**: `spring.threads.virtual.enabled: true` on every Tomcat-backed (I/O-bound) service.
- **Tests**: Testcontainers wired via `@ServiceConnection`, no manual `@DynamicPropertySource`.
- **Idempotency**: an optional `Idempotency-Key` header on `POST /orders` and `POST /payments/authorize`, backed by a partial-unique-indexed column — a retried request replays the original result (`200`, not `201`) instead of double-charging or double-reserving.
- **Outbox pattern**: order-service and payment-service write events to an `outbox_events` table in the same transaction as the business row, not a direct `KafkaTemplate.send()` — a `@Scheduled` poller drains it, so a crash between "commit" and "publish" can't silently drop an event.
- **Consumer idempotency + DLQ**: notification-service dedupes redelivered Kafka records on `(eventType, sourceId)`, and a `DefaultErrorHandler` (3 retries, then `payment-events.DLT`) replaces what used to be unbounded silent redelivery on a processing exception.
- **Correlation ID over Kafka**: the HTTP request's correlation id is captured into the outbox row and re-attached as a Kafka header on publish, then restored into the consumer's MDC — a trace survives the async gap, not just the synchronous Feign calls.
- **CORS, security headers, brute-force protection**: an explicit origin allow-list at the gateway (`globalcors`, not a service-level concern); `Content-Security-Policy`/`Referrer-Policy`/`Permissions-Policy` set at the gateway and, redundantly, on every service so calling one directly is still hardened; login gets its own IP-keyed, stricter rate limit plus a 5-attempt account lockout in user-service.

## Repository layout

```text
ecommerce-platform/ (this repo)
├── common/                  shared Kafka event DTOs, correlation-id constants
├── gateway-service/         Spring Cloud Gateway — routing, JWT auth, rate limiting
├── user-service/            identity, roles/permissions, JWT issuance
├── product-service/         catalog, categories
├── inventory-service/       stock, warehouses
├── order-service/           order lifecycle — reference implementation
├── payment-service/         payment authorization/capture
├── notification-service/    email delivery log, notification events
├── docs/architecture.html   architecture & design reference (all 22 parts)
├── docker-compose.yaml      full local stack: 6 Postgres instances, Kafka, Redis, all services,
│                            plus Prometheus/Grafana/OTel Collector/Jaeger/ELK
├── kubernetes/              cluster-scoped manifests (namespaces, ingress, Postgres StatefulSets, Kafka)
│   └── observability/       Prometheus, Grafana, OTel Collector, Jaeger, ELK, Filebeat — actually deployable
├── helm/ecommerce-platform/ umbrella Helm chart, one subchart per service, per-env values
├── monitoring/              Prometheus/OTel Collector/Filebeat configs (K8s + docker-compose variants),
│                            7 Grafana dashboards, Grafana provisioning, Logstash pipeline
├── postman/                 API collection + environments — see docs/local-deployment.html Part 7
├── loadtest/                k6 load tests against the gateway — peak-load.js (staged ramp),
│                            spike-load.js (sudden burst), soak-load.js (long sustained run),
│                            all sharing one traffic mix in loadtest/lib/flows.js
├── scripts/                 build-local-images.sh (builds all 7 images for a local K8s cluster),
│                            port-forward-persistent.sh (self-healing kubectl port-forwards)
├── Tiltfile                 one-command local K8s dev loop (`tilt up`) — builds images, applies every
│                            manifest above, deploys the Helm chart, wires up every port-forward
└── .github/workflows/       one CI/CD pipeline per service (7 total) — build → test → Sonar → package → Docker → deploy
```

## Running locally

```bash
docker compose up --build
```

Brings up all six Postgres databases, Kafka, Redis, all seven services, and the full observability stack. Gateway listens on `:8080`; each service is also reachable directly on its own port (see `docs/architecture.html` Part 21) for local debugging.

| Tool | URL |
|---|---|
| Grafana (dashboards pre-provisioned, anonymous viewer access) | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Jaeger UI | http://localhost:16686 |
| Kibana | http://localhost:5601 |

```bash
# Log in as the Flyway-seeded dev admin (user-service/src/main/resources/db/migration/V1__init_users.sql)
# — there's no public self-registration endpoint by design (see docs/architecture.html Part 7),
# so this account is what bootstraps everything else: creating products, stocking them, and
# creating the CUSTOMER accounts that can actually place orders.
curl -X POST localhost:8080/users/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Admin@12345"}'
```

For the full create-product → stock-it → create-customer → place-order walkthrough (curl and Postman both), see **[`docs/local-deployment.html`](docs/local-deployment.html)** Part 8.

## Running order-service's tests

```bash
./gradlew :order-service:test
```

Unit tests run against mocks; the Testcontainers-based repository test spins up a real `postgres:16-alpine` container — Docker must be running.

## Load testing

Three [k6](https://k6.io) scripts against a running stack (`docker compose up` or the Kubernetes path, either works — just point `BASE_URL` at the gateway), each asking a different question:

```bash
k6 run --env PROFILE=smoke loadtest/peak-load.js    # staged ramp: where does it degrade under sustained load?
k6 run --env PROFILE=smoke loadtest/spike-load.js   # sudden burst: does it shed load and recover cleanly?
k6 run --env PROFILE=smoke loadtest/soak-load.js    # long sustained run: does it stay healthy over time?
```

`PROFILE=smoke` is a ~1-minute sanity pass — drop it (or pass the script's named profile, e.g. `PROFILE=peak`) for the real run. All three share one realistic traffic mix (`loadtest/lib/flows.js`) so results are comparable across test shapes. Add `--out experimental-prometheus-rw=http://localhost:9090/api/v1/write` to watch a run live in Grafana instead of only terminal output — see [`loadtest/README.md`](loadtest/README.md). For real captured numbers without running anything yourself: [`docs/performance-baseline.html`](docs/performance-baseline.html), a dated snapshot comparing Docker Compose against Kubernetes.

## Deploying to Kubernetes

Against a real dev/qa cluster with its own image registry:

```bash
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/postgres/          # one StatefulSet per service database
kubectl apply -f kubernetes/kafka.yaml         # 3-broker KRaft cluster
kubectl apply -f kubernetes/redis.yaml
kubectl apply -f kubernetes/observability/     # Prometheus, Grafana, OTel Collector, Jaeger, ELK, Filebeat
helm upgrade --install ecommerce ./helm/ecommerce-platform -f ./helm/ecommerce-platform/values-dev.yaml -n ecommerce
kubectl apply -f kubernetes/ingress.yaml
```

Against a local single-node cluster (Docker Desktop's built-in Kubernetes) with no registry at all — build images locally first, then swap in `values-local.yaml`:

```bash
./scripts/build-local-images.sh
helm upgrade --install ecommerce ./helm/ecommerce-platform -f ./helm/ecommerce-platform/values-local.yaml -n ecommerce --create-namespace
kubectl port-forward -n ecommerce svc/gateway-service 8080:80
```

Or skip all of the above — `tilt up` (`brew install tilt`) automates image builds, every manifest, the Helm release, and every port-forward into one command, with a live UI showing build/deploy/log status. `tilt ci` does the same non-interactively (apply, wait for health, exit) for scripting. See `docs/local-deployment.html` Part 4.

Full walkthrough with troubleshooting: **[`docs/local-deployment.html`](docs/local-deployment.html)**. See `docs/architecture.html` Part 14 for the production recommendation (managed Postgres over self-hosted StatefulSets) and Part 20 for the full production-readiness checklist before promoting past `uat`.

## Learning path

If you're working through this platform to learn the stack rather than to ship it, start with [`docs/learning-guide.html`](docs/learning-guide.html) — Spring Boot layers → data → REST APIs → sync/async service communication → reliability patterns → security → containers/orchestration → observability, each with a junior-level takeaway (what to understand to get productive) and a lead-level takeaway (the tradeoff worth understanding at a senior level, linking into the deeper reference in `docs/architecture.html`).
