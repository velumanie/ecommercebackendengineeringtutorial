# Learn the Stack

*Onboarding*

The same technologies as `docs/architecture.html`, but as a path instead of a reference — read this top to bottom once, skipping anything you already know. Every section has two boxes: **Junior** is what you need to get productive; **Lead** is the tradeoff worth understanding once you're the one making the call. Every pointer below is a real file in this repo, not a hypothetical.

`Java 25` `Spring Boot 4.1` `PostgreSQL` `Kafka` `Docker` `Kubernetes` `Helm` `Prometheus` `Kibana`

## Contents

- [00 Start Here](#start)
- [01 How This Repo Teaches Itself](#shape)
- [02 Spring Boot Layers](#spring)
- [03 Data — Postgres + Flyway](#data)
- [04 REST API Design](#api)
- [05 Talking Between Services](#comms)
- [06 Reliability Patterns](#reliability)
- [07 Security](#security)
- [08 Containers & Orchestration](#containers)
- [09 Observability](#observability)
- [10 Where To Go Next](#next)

<a id="start"></a>
## 00 — Start Here

Don't read code first. Run the thing, poke at it, then come back — everything below will make more sense once you've seen it work.

```bash
docker compose up --build
curl -X POST localhost:8080/users/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Admin@12345"}'
```

That's the whole platform — 7 services, 6 databases, Kafka, and the full observability stack — on your laptop. Full walkthrough (including placing an order end to end): [docs/local-deployment.html](local-deployment.md).

<a id="shape"></a>
## 01 — How This Repo Teaches Itself

`order-service` is the complete reference implementation: every layer, full tests, Dockerfile, Kubernetes manifests, Helm chart. The other five services (`user`, `product`, `inventory`, `payment`, `notification`) copy its shape exactly. Read `order-service` once, carefully — you can skim the rest, because they're the same pattern applied to a different domain.

> **Junior:** Open `order-service/src/main/java/com/ecommerce/order/` and just look at the folder names. That list *is* the architecture — nothing hidden, nothing to memorize separately.

> **Lead:** The full repo tree with every service, chart, and pipeline is [architecture.html Part 21](architecture.md#part21) — useful once you're navigating six services at once, not before.

<a id="spring"></a>
## 02 — Spring Boot Layers

Every service is the same stack, top to bottom: `controller` (HTTP in/out) → `service` (business logic) → `repository` (Spring Data JPA) → `entity` (the table). `dto`/`mapper` keep the wire format separate from the persisted shape; `config` is where beans get wired; `exception` is the error contract; `event`/`integration` is how a service talks to the others (Part 5).

> **Junior:** Trace one real request: `OrderController.create()` → `OrderServiceImpl.createOrder()` → `OrderRepository` → the `Order` entity. Four files, one request, that's the whole pattern.

> **Lead:** Why this shape instead of a thinner "fat controller" or a CQRS split: [architecture.html Part 4](architecture.md#part4).

<a id="data"></a>
## 03 — Data — PostgreSQL + Flyway

Database-per-service — no service reads another's tables directly. Schema changes are files, not ORM auto-DDL: every table this platform has ever had is one `CREATE TABLE` away in a Flyway migration, checked into git.

> **Junior:** Open `order-service/src/main/resources/db/migration/V1__init_orders.sql`. That file *is* the schema — no separate diagram to keep in sync, no magic. Compare it against `Order.java`'s `@Column` annotations to see how JPA maps onto it.

> **Lead:** Partitioning, indexing choices, and why cross-service references are plain UUIDs with no foreign key: [architecture.html Part 2](architecture.md#part2).

<a id="api"></a>
## 04 — REST API Design

Controllers never accept or return entities directly — always a request/response DTO, validated with Bean Validation (`@NotNull`, `@Min`, etc.). Every error is RFC 9457 `application/problem+json`, produced by one `@RestControllerAdvice` per service, not scattered try/catch blocks.

> **Junior:** Look at `OrderRequest`/`OrderResponse` next to `Order` — notice what's missing from the DTOs (internal fields like `version`) and why. Then check `GlobalExceptionHandler` to see every domain exception mapped to one status + problem type.

> **Lead:** Full conventions (versioning, pagination, filtering) and the OpenAPI situation: [architecture.html Part 3](architecture.md#part3).

<a id="comms"></a>
## 05 — Talking Between Services

Two shapes, chosen by whether the caller needs to wait for an answer. **Synchronous** (OpenFeign): order-service calls inventory-service to check stock and needs the result before it can respond — wrapped in a circuit breaker with a fallback. **Asynchronous** (Kafka): payment-service finishes a payment and notification-service needs to know eventually, not right now — published as an event, not a blocking call.

> **Junior:** Compare `InventoryClient` (a Feign interface — looks like a normal method call) against `OrderEventProducer` (writes a row, doesn't call anything directly). Same underlying need — "tell another service something" — two different shapes because the failure semantics differ: a failed Feign call fails the request now; a failed Kafka publish should never fail the request that triggered it.

> **Lead:** Why events are written to an outbox table instead of published directly, how the correlation ID survives the async gap, and the dead-letter policy: [architecture.html Part 6](architecture.md#part6).

<a id="reliability"></a>
## 06 — Reliability Patterns

Idempotency keys, the transactional outbox, circuit breakers, consumer deduplication — every one of these exists in this codebase because of a real bug this platform actually had at some point, not as a textbook exercise. That's on purpose: the fix is more useful to read than the pattern's name.

> **Junior:** What "idempotent" means, concretely: send the exact same `POST /orders` request twice with the same `Idempotency-Key` header. You get the same order back both times — `201` then `200` — never two orders and never two charges.

> **Lead:** The full resilience config (circuit breaker/retry/bulkhead/rate limiter) and the production-readiness checklist it feeds into: [Part 18](architecture.md#part18), [Part 20](architecture.md#part20).

<a id="security"></a>
## 07 — Security

user-service issues a JWT once at login; every other service validates it independently via Spring Security's OAuth2 Resource Server — nobody trusts the gateway blindly, so calling a service directly is still safe. Roles map to Spring's `hasRole()`/`hasAnyRole()`. On top of that: rate limiting at the gateway and account lockout in user-service, both defending against brute-force login attempts specifically.

> **Junior:** Hit an endpoint with no token at all, then again with a valid token but the wrong role. You'll get `401` and `403` respectively — they're not interchangeable, and knowing which one applies is the whole point of the exercise.

> **Lead:** JWT flow, refresh token revocation, RBAC model, CORS/security-header posture: [architecture.html Part 7](architecture.md#part7).

<a id="containers"></a>
## 08 — Containers & Orchestration

Every service has its own multi-stage `Dockerfile`. Kubernetes manifests and one umbrella Helm chart (per-environment `values-*.yaml`) turn that into something that self-heals, scales, and rolls out without downtime — none of which Docker Compose does on its own.

> **Junior:** Run both paths once — Compose, then `tilt up` for local Kubernetes — and notice what's identical (the app) versus what's different (how it's supervised). [docs/local-deployment.html](local-deployment.md) is the exact walkthrough.

> **Lead:** Service discovery, Ingress, StatefulSets for Postgres, Helm chart structure: [architecture.html Parts 11–15](architecture.md#part11).

<a id="observability"></a>
## 09 — Observability

Three pillars, one thread tying them together: metrics (Prometheus/Grafana), traces (Jaeger via OpenTelemetry), and logs (the ELK stack) are all correlated by a single `correlationId` that's generated once at the gateway and threaded through every HTTP call *and* every Kafka message from there on.

> **Junior:** Place an order, grab the `X-Correlation-Id` response header, and search for it in Kibana. Watch one request's footprint across every service it touched.

> **Lead:** How each pillar is wired and what actually breaks in practice: [architecture.html Parts 9–10](architecture.md#part9) for the design, [local-deployment.html Part 9](local-deployment.md#obs-debug) for real failure modes and how to debug them.

<a id="next"></a>
## 10 — Where To Go Next

| You want to… | Read |
| --- | --- |
| Understand *why* a specific design decision was made | [docs/architecture.html](architecture.md) — the full 22-part reference |
| Actually run it, locally or in Kubernetes | [docs/local-deployment.html](local-deployment.md) |
| See it work end to end without writing any code yourself | `postman/` — import and run the collection |
| Load-test it and see where it degrades | `loadtest/` — `peak-load.js` (gradual ramp), `spike-load.js` (sudden burst), `soak-load.js` (long sustained run); all three share one traffic mix in `loadtest/lib/flows.js`. Start with `k6 run --env PROFILE=smoke loadtest/peak-load.js` |
| See real captured numbers without running anything yourself | [docs/performance-baseline.html](performance-baseline.md) — a dated snapshot comparing Docker Compose against Kubernetes on the same hardware |
| Build it yourself from nothing, not just read the finished version | [docs/tutorial.html](tutorial.md) — same technologies as this page, but as a phased, from-scratch build with real code and config at every step |

---

Learning path for the six-service e-commerce platform. Pairs with `docs/architecture.html` (design reference) and `docs/local-deployment.html` (operations reference) — this document is the on-ramp to both.
