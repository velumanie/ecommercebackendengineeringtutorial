# Build It Yourself, Step by Step

*All 11 phases complete*

Every other doc in `docs/` explains a *finished* platform. This one builds it — starting from one Spring Boot service with nothing else running, adding exactly one new capability per phase, in the order this platform's own development actually happened. Every snippet below is real code from this repo, trimmed back to what exists *at this point in the build* — later phases add fields and files this one deliberately leaves out, and each addition is called out explicitly rather than sprung on you.

`Java 25` `Spring Boot 4.1` `PostgreSQL` `Flyway` `OpenFeign`

## Contents

- [00 — How This Works](#intro)
- [01 — One Service, Standalone](#phase1)
- [02 — A Real REST API](#phase2)
- [03 — Sync Integration (Feign)](#phase3)
- [04 — Security & OAuth2](#phase4)
- [05 — Gateway In Front](#phase5)
- [06 — Kafka & the Outbox](#phase6)
- [07 — Hardening It](#phase7)
- [08 — Containerize](#phase8)
- [09 — Kubernetes & Helm](#phase9)
- [10 — Observability](#phase10)
- [11 — Load Test & Tune](#phase11)

<a id="intro"></a>
## 00 — How This Works

- **One phase, one new capability.** Phase 1 is deliberately incomplete — no auth, no Kafka, no second service. That's not a simplification for teaching purposes; it's genuinely how you'd start.
- **Every code block is real**, copied from this repo and trimmed to match the phase, not pseudocode. A `filename` caption above each block tells you exactly where it lives once the full build is done.
- **Depth is uneven on purpose.** `order-service` is the vehicle for teaching every concept in this tutorial — go deep there. The other five services in the finished platform repeat its shape; once you've built one from scratch, you don't need to build all six to understand the rest (see the finished versions directly if you want to check the pattern held).
- All 11 phases are live below — one service standalone, a real REST API, sync integration, security/OAuth2, the gateway, Kafka with the outbox pattern, hardening every gap the earlier phases deliberately left open, containerizing the result, deploying it to Kubernetes via Helm, making all of it observable, and finally proving it under real concurrent load.

<a id="phase1"></a>
## 01 — One Service, Standalone

> **Goal:** `order-service` boots, connects to Postgres, and its schema exists — nothing else yet. No REST endpoints, no other services, no security.

1. **Start Postgres.** Nothing fancy — a plain container is enough while you're building one service in isolation:

```bash
docker run -d --name orders-db -e POSTGRES_DB=orders_db \
  -e POSTGRES_USER=orders_user -e POSTGRES_PASSWORD=orders_pass \
  -p 5432:5432 postgres:16-alpine
```

2. **Declare the dependencies you need right now** — not the ones you'll need in phase 7. Every dependency below earns its place in *this* phase specifically:

**`order-service/build.gradle`**
```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'

    runtimeOnly 'org.postgresql:postgresql'
    implementation 'org.springframework.boot:spring-boot-starter-flyway'
    implementation 'org.flywaydb:flyway-core'
    runtimeOnly 'org.flywaydb:flyway-database-postgresql'
}
```

> **Why Flyway, not `ddl-auto: update`**
>
> Hibernate can generate and apply schema changes for you — and it's exactly what you should *not* use past a prototype. Auto-DDL is unreviewable (no diff, no code review), unpredictable (Hibernate's inference from annotations isn't always what you'd hand-write), and has no rollback story. A Flyway migration is a plain SQL file, checked into git, applied in order, once, ever. This project uses `ddl-auto: validate` everywhere — Hibernate is only ever allowed to *check* the schema matches the entities, never change it.

3. **Write the schema by hand** — the migration *is* the schema, not a side effect of the entity classes:

**`order-service/src/main/resources/db/migration/V1__init_orders.sql`**
```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE orders (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id   UUID NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_amount  NUMERIC(12,2) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_orders_status CHECK (status IN
        ('PENDING','CONFIRMED','PAID','SHIPPED','DELIVERED','CANCELLED','FAILED')),
    CONSTRAINT ck_orders_total_amount CHECK (total_amount >= 0)
);

CREATE INDEX idx_orders_customer ON orders (customer_id, created_at DESC);

CREATE TABLE order_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id  UUID NOT NULL,
    quantity    INT NOT NULL,
    unit_price  NUMERIC(12,2) NOT NULL,
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);
```

> **Why `gen_random_uuid()`, not an auto-increment integer**
>
> A sequential integer PK leaks how many rows exist and in what order (a competitor watching order IDs can estimate your order volume). A client-generatable UUID also means an `Order` object can get an ID the moment it's constructed in Java, before it's ever persisted — useful the instant you need to reference it before the INSERT completes.

4. **Map the schema to entities** — deliberately minimal for this phase. No idempotency key, no outbox reference, nothing from later phases:

**`order-service/src/main/java/com/ecommerce/order/entity/Order.java`**
```java
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    public static Order create(UUID customerId, List<OrderItem> items, BigDecimal totalAmount) {
        Order order = new Order();
        order.customerId = customerId;
        order.totalAmount = totalAmount;
        order.status = OrderStatus.PENDING;
        items.forEach(order::addItem);
        return order;
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
```

> **Three details worth noticing now, before they're habit**
>
> `@NoArgsConstructor(access = PROTECTED)` — JPA requires a no-args constructor to exist, but nothing outside this class should ever call it directly; `protected` forces every other caller through `Order.create(...)`, which is the only place that can produce a valid, fully-initialized order. `@Setter` on the whole class is a pragmatic tradeoff, not a recommendation — it's convenient for JPA and Lombok, but it means nothing stops code elsewhere from calling `order.setStatus(...)` directly instead of going through a method that enforces valid transitions; that gap gets closed in phase 2's service layer, not the entity. `@Version` is optimistic locking — a concurrent update to the same row fails loudly (`OptimisticLockException`) instead of silently overwriting.

**`order-service/src/main/java/com/ecommerce/order/entity/OrderItem.java`**
```java
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    public static OrderItem of(UUID productId, int quantity, BigDecimal unitPrice) {
        OrderItem item = new OrderItem();
        item.productId = productId;
        item.quantity = quantity;
        item.unitPrice = unitPrice;
        return item;
    }

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
```

5. **One repository interface** — Spring Data JPA writes the implementation for you at runtime:

**`order-service/src/main/java/com/ecommerce/order/repository/OrderRepository.java`**
```java
public interface OrderRepository extends JpaRepository<Order, UUID> {
}
```

6. **Point Spring at the database:**

**`order-service/src/main/resources/application.yml`**
```yaml
spring:
  application:
    name: order-service
  datasource:
    url: jdbc:postgresql://localhost:5432/orders_db
    username: orders_user
    password: orders_pass
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    locations: classpath:db/migration

server:
  port: 8084
```

> **Why `open-in-view: false`, set here on day one**
>
> Spring Boot's default (`true`) keeps the Hibernate session open for the entire HTTP request, so a lazy-loaded association can be fetched from a Thymeleaf template without a `LazyInitializationException`. This platform has no server-rendered views — every response is JSON, assembled deliberately in the service layer — so that convenience is pure downside: it hides N+1 queries and keeps a DB connection checked out for the whole request instead of just the transaction. Turning it off on day one means you find lazy-loading mistakes immediately, as a clear exception, instead of as a mysterious slow endpoint months later.

7. **The application class** — nothing but the annotation Spring Boot needs to find everything else on the classpath:

**`order-service/src/main/java/com/ecommerce/order/OrderServiceApplication.java`**
```java
@SpringBootApplication
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

#### ✓ Checkpoint

`./gradlew :order-service:bootRun` should start cleanly, Flyway should log `Successfully applied 1 migration`, and `curl localhost:8084/actuator/health` — wait, actuator isn't a dependency yet, so that specific check comes later. For now: no startup errors, and `psql orders_db -c '\dt'` shows `orders` and `order_items`. That's the whole goal of this phase — a service that boots and owns a real, versioned schema. It doesn't do anything useful yet; phase 2 fixes that.

<a id="phase2"></a>
## 02 — A Real REST API

> **Goal:** `POST /api/v1/orders` and `GET /api/v1/orders/{id}` work end to end — with validated input, a response shape that isn't just the entity, and errors that look like errors instead of stack traces.

1. **Add validation to the dependency list:**

**`order-service/build.gradle`**
```groovy
implementation 'org.springframework.boot:spring-boot-starter-validation'
```

2. **DTOs — never put `@Entity` classes on the wire.** The request and response shapes are their own types:

**`order-service/src/main/java/com/ecommerce/order/dto/OrderRequest.java`**
```java
public record OrderRequest(
        @NotNull UUID customerId,
        @NotEmpty @Valid List<OrderItemRequest> items) {
}
```
**`order-service/src/main/java/com/ecommerce/order/dto/OrderItemRequest.java`**
```java
public record OrderItemRequest(
        @NotNull UUID productId,
        @Min(1) @Max(999) int quantity) {
}
```
**`order-service/src/main/java/com/ecommerce/order/dto/OrderResponse.java`**
```java
public record OrderResponse(
        UUID id,
        UUID customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt) {
}
```

> **Why not just return the entity**
>
> Three reasons, in order of how quickly they bite you. First, `Order.items` is lazy — serializing it outside a transaction throws. Second, an entity's shape is coupled to the database; adding an internal-only column (an idempotency key, in phase 7) would leak straight into every API response unless the DTO is a deliberate, separate boundary. Third — the request and response DTOs aren't even the same fields: a request needs `customerId` and item quantities; a response needs computed prices and a server-assigned `id`. Reusing one class for both means either accepting fields a client shouldn't set, or awkwardly nulling things out — a real category of security bug (mass-assignment) that a separate request DTO makes structurally impossible.

3. **The service layer** — this is where `Order.create(...)` actually gets called, and where business rules that don't belong on the entity or the controller live:

**`order-service/src/main/java/com/ecommerce/order/service/impl/OrderServiceImpl.java` (excerpt)**
```java
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    @Override
    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        var items = request.items().stream()
                .map(i -> OrderItem.of(i.productId(), i.quantity(), BigDecimal.TEN)) // price lookup arrives in phase 3
                .toList();

        BigDecimal total = items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order saved = orderRepository.save(Order.create(request.customerId(), items, total));
        return orderMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        return orderMapper.toResponse(order);
    }
}
```

> **Deliberately not production code yet**
>
> That hardcoded `BigDecimal.TEN` is a placeholder — this phase doesn't have a product catalog to price against yet (that's phase 3's actual reason for existing: order-service needs to *ask another service* for a real price and stock check). Building a fake local price table here would be throwaway work; better to feel the gap and let phase 3 fill it for a real reason.

4. **The controller** — thin on purpose. It translates HTTP into a service call and a service result into an HTTP response; it has no business logic of its own:

**`order-service/src/main/java/com/ecommerce/order/controller/OrderController.java` (excerpt)**
```java
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return orderService.getOrder(id);
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderRequest request) {
        OrderResponse body = orderService.createOrder(request);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + body.id())).body(body);
    }
}
```

> **Why `@Valid` on the parameter, not inside the method**
>
> `@Valid` triggers Bean Validation *before* the method body runs — an invalid request never reaches your business logic at all, it's rejected by a `MethodArgumentNotValidException` Spring throws on your behalf. That's what step 5 turns into a proper error response instead of a generic 400 with no detail.

5. **Domain exceptions, and one place that turns all of them into real HTTP responses:**

**`order-service/src/main/java/com/ecommerce/order/exception/OrderNotFoundException.java`**
```java
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(UUID orderId) {
        super("Order not found: " + orderId);
    }
}
```
**`order-service/src/main/java/com/ecommerce/order/exception/GlobalExceptionHandler.java` (excerpt)**
```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final URI PROBLEM_BASE = URI.create("https://api.company.com/problems/");

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleNotFound(OrderNotFoundException ex, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "order-not-found", "Order Not Found", ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> "%s %s".formatted(fe.getField(), fe.getDefaultMessage()))
                .orElse("Validation failed");
        return problem(HttpStatus.BAD_REQUEST, "validation-error", "Validation Failed", detail, req);
    }

    private ProblemDetail problem(HttpStatus status, String typeSuffix, String title, String detail,
                                   HttpServletRequest req) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(PROBLEM_BASE.resolve(typeSuffix));
        problem.setTitle(title);
        problem.setInstance(URI.create(req.getRequestURI()));
        return problem;
    }
}
```

> **Why one handler class instead of try/catch in every controller method**
>
> `@RestControllerAdvice` intercepts exceptions from *every* controller in the service — write the "how do I turn this failure into an HTTP response" logic exactly once. Returning Spring's own `ProblemDetail` (rather than a hand-rolled error DTO) means the response is [RFC 9457](https://datatracker.ietf.org/doc/html/rfc9457)-shaped for free — `type`, `title`, `status`, `detail`, `instance` are all fields a generic HTTP client already knows how to parse, not a bespoke shape every client has to learn.

#### ✓ Checkpoint

```bash
curl -i -X POST localhost:8084/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"9c2f6e1a-....","items":[{"productId":"7ab1....","quantity":2}]}'
# 201 Created, Location header, a real OrderResponse body

curl -i -X POST localhost:8084/api/v1/orders -H 'Content-Type: application/json' -d '{}'
# 400 Bad Request, application/problem+json, detail: "customerId must not be null"

curl -i localhost:8084/api/v1/orders/00000000-0000-0000-0000-000000000000
# 404 Not Found, application/problem+json
```

A real, independently useful API — you could stop here and have something shippable, just not integrated with anything else yet. That's exactly what phase 3 changes.

<a id="phase3"></a>
## 03 — Synchronous Integration (OpenFeign)

> **Goal:** order-service asks a second, independently-running service — inventory-service — whether stock exists before confirming an order, over real HTTP, with a defined behavior for when that call fails.

1. **The other side first — a minimal inventory-service** with one endpoint order-service is actually going to call. Building the callee before the caller means you can test each side independently:

**`inventory-service/src/main/java/com/ecommerce/inventory/controller/InventoryController.java` (excerpt)**
```java
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/{productId}/availability")
    public StockAvailabilityResponse checkAvailability(@PathVariable UUID productId,
                                                         @RequestParam int quantity) {
        return inventoryService.checkAvailability(productId, quantity);
    }

    @PostMapping("/{productId}/reserve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reserve(@PathVariable UUID productId, @Valid @RequestBody ReserveStockRequest request) {
        inventoryService.reserve(productId, request.quantity());
    }
}
```

Same phase-1/phase-2 pattern as order-service — entity, repository, service, controller, its own Postgres, its own Flyway migration. Build it the same way; the point of this phase isn't repeating that, it's what comes next.

2. **Add Feign to order-service** and turn it on:

**`order-service/build.gradle`**
```groovy
implementation 'org.springframework.cloud:spring-cloud-starter-openfeign'
```
**`order-service/src/main/java/com/ecommerce/order/OrderServiceApplication.java`**
```java
@SpringBootApplication
@EnableFeignClients
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

3. **Declare the client as an interface** — Feign generates the HTTP implementation at runtime, so calling another service reads exactly like calling a local method:

**`order-service/src/main/java/com/ecommerce/order/integration/InventoryClient.java`**
```java
@FeignClient(name = "inventory-service")
public interface InventoryClient {

    @GetMapping("/api/v1/inventory/{productId}/availability")
    StockAvailabilityResponse checkAvailability(@PathVariable("productId") UUID productId,
                                                 @RequestParam("quantity") int quantity);

    @PostMapping("/api/v1/inventory/{productId}/reserve")
    void reserve(@PathVariable("productId") UUID productId, @RequestBody ReserveStockRequest request);
}
```
**`order-service/src/main/java/com/ecommerce/order/integration/dto/StockAvailabilityResponse.java`**
```java
public record StockAvailabilityResponse(UUID productId, boolean available, int quantityOnHand) {
}
```

Wire it into `application.yml` so `"inventory-service"` resolves to somewhere real:

**`order-service/src/main/resources/application.yml`**
```yaml
spring:
  cloud:
    discovery:
      client:
        simple:
          instances:
            inventory-service[0]:
              uri: http://localhost:8083
```

4. **Run both services and watch it work** — then watch it break. Stop inventory-service and call order-service's create endpoint again:

```bash
curl -i -X POST localhost:8084/api/v1/orders -H 'Content-Type: application/json' -d '{...}'
# 500 Internal Server Error
# {"type":".../internal-error","detail":"An unexpected error occurred"}
```

> **This is the bug this phase exists to fix**
>
> A raw `FeignException` (connection refused) fell all the way through to `GlobalExceptionHandler`'s generic `Exception` handler and came out as a *500* — indistinguishable from an actual bug in order-service's own code, when the real problem is "a dependency is down," which is a completely different, expected, plannable failure mode. A caller (and you, reading the logs at 2am) needs to be able to tell those apart.

5. **A fallback factory** translates that failure into a domain exception on purpose, instead of leaving it to fall through as a generic 500:

**`order-service/src/main/java/com/ecommerce/order/integration/InventoryClientFallbackFactory.java`**
```java
@Component
public class InventoryClientFallbackFactory implements FallbackFactory<InventoryClient> {

    private static final Logger log = LoggerFactory.getLogger(InventoryClientFallbackFactory.class);

    @Override
    public InventoryClient create(Throwable cause) {
        return new InventoryClient() {
            @Override
            public StockAvailabilityResponse checkAvailability(UUID productId, int quantity) {
                log.warn("inventory-service unavailable while checking availability for {}", productId, cause);
                throw new InventoryUnavailableException(productId, cause);
            }

            @Override
            public void reserve(UUID productId, ReserveStockRequest request) {
                log.warn("inventory-service unavailable while reserving stock for {}", productId, cause);
                throw new InventoryUnavailableException(productId, cause);
            }
        };
    }
}
```

Wire it onto the client, and add a matching handler to `GlobalExceptionHandler` so it comes out as a proper `409`, not a `500`:

**`order-service/src/main/java/com/ecommerce/order/integration/InventoryClient.java`**
```java
@FeignClient(name = "inventory-service", fallbackFactory = InventoryClientFallbackFactory.class)
public interface InventoryClient {
    // ...unchanged...
}
```
```java
@ExceptionHandler(InventoryUnavailableException.class)
public ProblemDetail handleInventory(InventoryUnavailableException ex, HttpServletRequest req) {
    return problem(HttpStatus.CONFLICT, "inventory-unavailable", "Inventory Unavailable", ex.getMessage(), req);
}
```

> **Why a factory, not a plain fallback instance**
>
> Feign supports a simpler `fallback = SomeBean.class` too — but a plain fallback has no access to *why* the call failed, so `log.warn(...)` above would have nothing useful to say. `FallbackFactory` receives the actual `Throwable`, which is the difference between a log line that says "inventory-service unavailable" and one that says that *and* shows the real connection-refused/timeout/5xx underneath it — the thing you'll actually need at 2am.

6. **Two headers every Feign call needs to forward** — not obvious until you notice they're silently missing:

**`order-service/src/main/java/com/ecommerce/order/config/FeignClientConfig.java`**
```java
@Configuration
public class FeignClientConfig {

    // Without this, inventory-service's own logs have no way to connect a request back to
    // the order-service call that triggered it — each service's logs become an island.
    @Bean
    public RequestInterceptor correlationIdPropagationInterceptor() {
        return template -> {
            String correlationId = MDC.get(CorrelationIdConstants.MDC_KEY);
            if (correlationId != null) {
                template.header(CorrelationIdConstants.HEADER, correlationId);
            }
        };
    }

    // inventory-service validates its own caller's JWT independently — it doesn't blindly
    // trust that "this request came from order-service" is enough. Without this interceptor,
    // every Feign call from an authenticated request would arrive at inventory-service with
    // no token at all and get rejected with 401, even though the original caller was valid.
    @Bean
    public RequestInterceptor bearerTokenPropagationInterceptor() {
        return template -> {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttributes) {
                String authorization = servletAttributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
                if (authorization != null) {
                    template.header(HttpHeaders.AUTHORIZATION, authorization);
                }
            }
        };
    }
}
```

Reference it from the client:

```java
@FeignClient(name = "inventory-service", configuration = FeignClientConfig.class,
             fallbackFactory = InventoryClientFallbackFactory.class)
public interface InventoryClient { /* ... */ }
```

> **Neither of these has a payoff yet**
>
> Correlation ID propagation is invisible until phase 10 (Observability), when you'll search Kibana for one ID and expect to see the request's whole cross-service trail. Auth forwarding is invisible until the very next phase, which builds the service issuing these tokens and makes every downstream service validate them independently. Wiring both now, while the reason is still abstract, means neither phase needs to come back and retrofit this file — the integration point exists once, correctly, from here on.

#### ✓ Checkpoint

With both services running: placing an order calls out to inventory-service in real time and the response reflects real stock. Stop inventory-service and the same request now returns a clean `409 inventory-unavailable` instead of a `500` — a caller (or a retry policy, added in phase 7) can actually act on that distinction. This is also the first point where "the platform" stops being one service and starts being a *system* — every request above ran unauthenticated, which the next phase fixes before anything else gets built on top of it. Phase 5 then puts a gateway in front so a client only ever talks to one address, and phase 6 adds the asynchronous half of inter-service communication that a synchronous call like this one is deliberately not suited for.

<a id="phase4"></a>
## 04 — Security & OAuth2

> **Goal:** a new service, `user-service`, that authenticates a user and issues a token. Every other service — `order-service` included — validates that token completely independently, with no network call back to user-service on every request. Understanding *why* that's possible is most of this phase; the Spring config is short once the concept is clear.

### First, without any code: what OAuth2.0 actually is

Worth getting precise about, because the name gets used loosely. **OAuth 2.0 is an authorization framework, not an authentication protocol** — it's a standard way for a *client* to get a token proving it has permission to act on a *resource owner's* behalf, without ever seeing that owner's password. It defines four roles:

| Role | In the classic OAuth2 story | In this platform |
|---|---|---|
| Resource Owner | The end user | The customer/admin logging in |
| Client | The app requesting access on the owner's behalf | Whatever calls the API — a frontend, Postman, another service |
| Authorization Server | Issues tokens after verifying identity/consent | `user-service` |
| Resource Server | Hosts the protected resource, accepts the token | Every service — `order-service`, `payment-service`, all of them, *including* `user-service` for its own endpoints |

The spec defines several **grant types** — different recipes for how a client gets a token, chosen based on how much you trust the client: *Authorization Code* (the one behind "Sign in with Google" — the user authenticates on the authorization server's own page, never handing credentials to the client at all), *Client Credentials* (service-to-service, no user involved), and the largely-deprecated *Resource Owner Password Credentials* — the client collects a username and password directly and exchanges them for a token in one call.

> **Where this platform's login honestly sits — and doesn't**
>
> `POST /api/v1/auth/login` below is shaped like the Password grant: email and password go in, a token comes out, in one request. The spec discourages that grant for third-party clients specifically because it requires the client to handle the raw password — a real security risk if you don't fully trust whoever's calling. It's still a reasonable, common pattern for a *first-party* system where the "client" is your own frontend calling your own backend, which is the case here. What this build does *not* implement: client registration, the Authorization Code flow, PKCE, consent screens, or scopes — the full machinery a spec-complete OAuth2 Authorization Server (Spring Authorization Server, Keycloak, Auth0) provides. This is deliberately a simplified, first-party-only JWT issuance pattern that borrows OAuth2's Resource Server *validation* ideas, not a complete implementation of the spec. Knowing exactly which parts are and aren't here is the difference between using this pattern correctly and accidentally exposing it to a third party it was never designed for.

> **One more distinction worth having: OAuth2 vs OpenID Connect**
>
> OAuth2 alone answers "is this request authorized?" — it says nothing about *who* the user is beyond an opaque token. **OpenID Connect (OIDC)** is a thin identity layer built on top of OAuth2 that adds an actual authentication result — the `id_token`, itself a JWT with standardized identity claims (`sub`, `email`, `name`). "Log in with Google" is OIDC, not bare OAuth2. This platform's access token carries identity claims directly (`sub`, `email`, `roles`) without a separate `id_token` — a simplification that works because there's exactly one first-party client, not an ecosystem of third-party ones that would need a standardized identity contract.

Neither OAuth2 nor OIDC mandates a token *format* — an access token can be an opaque random string the resource server checks against the authorization server on every request (a network call, but instantly revocable), or a **JWT**: a self-contained, cryptographically signed token the resource server can verify entirely on its own, no network call, at the cost of not being revocable before it naturally expires. This platform uses JWTs for exactly that trade: an order placed under load shouldn't cost order-service a network round-trip to user-service just to check who's asking.

### Issuing a token — build `user-service`

1. **Dependencies** — `spring-boot-starter-security` and `spring-boot-starter-oauth2-resource-server` together, plus the JWT library actually doing the signing:

**`user-service/build.gradle`**
```groovy
implementation 'org.springframework.boot:spring-boot-starter-web'
implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
implementation 'org.springframework.boot:spring-boot-starter-validation'

implementation 'io.jsonwebtoken:jjwt-api:0.13.0'
runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.13.0'
runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.13.0'
```

> **Two different security starters, two different jobs**
>
> `spring-boot-starter-security` gives you the framework — `SecurityFilterChain`, `PasswordEncoder`, the whole authentication/authorization machinery. `spring-boot-starter-oauth2-resource-server` is specifically the piece that validates an incoming bearer token — `JwtDecoder`, the `oauth2ResourceServer()` DSL. `jjwt` (io.jsonwebtoken) is unrelated to either — it's what actually *builds and signs* a token, which only `user-service` ever needs to do. Every other service in this platform takes the second dependency without the third: they validate tokens, they never mint one.

2. **The schema and entities** — a user and a set of roles, nothing else yet (account lockout is a phase 7 addition):

**`user-service/src/main/resources/db/migration/V1__init_users.sql` (excerpt)**
```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    first_name     VARCHAR(100) NOT NULL,
    last_name      VARCHAR(100) NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE roles (
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name  VARCHAR(50) NOT NULL,
    CONSTRAINT uq_roles_name UNIQUE (name)
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);

INSERT INTO roles (name) VALUES ('ADMIN'), ('MANAGER'), ('CUSTOMER');
```
**`user-service/src/main/java/com/ecommerce/user/entity/User.java` (excerpt)**
```java
@Entity
@Table(name = "users")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    private String firstName;
    private String lastName;

    @ManyToMany
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    public static User create(String email, String passwordHash, String firstName, String lastName) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.firstName = firstName;
        user.lastName = lastName;
        return user;
    }
}
```

> **The single most important line in this entire phase**
>
> The field is `passwordHash`, never `password`. A plaintext password must never exist anywhere past the moment it arrives in the login request — not in a variable that outlives the request, not in a log line, not in the database. What actually produces that hash is the next step.

3. **Hash passwords with BCrypt** — one bean, registered once:

**`user-service/src/main/java/com/ecommerce/user/security/SecurityConfig.java` (excerpt)**
```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
}
```

> **Why BCrypt, specifically — not SHA-256, not MD5**
>
> A general-purpose hash like SHA-256 is *fast* — a property that's exactly wrong for passwords. Fast means an attacker with a stolen hash table can try billions of candidate passwords a second on commodity hardware. BCrypt is deliberately, tunably *slow* (the `12` above is the cost factor — each increment roughly doubles the work), and generates a random salt automatically per password, so two users with the same password get different hashes and a precomputed rainbow table is useless against it. Contrast this with phase 3's `SHA-256` hashing of *refresh tokens*, which is correct there for the opposite reason: a refresh token already has 256 bits of real randomness (`UUID.randomUUID()` twice, concatenated) — there's no low-entropy human guess to defend against, so a fast hash is fine, and BCrypt's slowness there would just be wasted CPU on every token refresh. Same-looking problem, opposite right answer, because the input entropy is completely different.

4. **The login flow** — verify the password, issue a token:

**`user-service/src/main/java/com/ecommerce/user/service/impl/AuthServiceImpl.java` (excerpt)**
```java
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(InvalidCredentialsException::new);

        String accessToken = jwtService.generateAccessToken(user);
        return new LoginResponse(accessToken, jwtService.accessTtlSeconds());
    }
}
```

> **One line doing two jobs on purpose**
>
> `.filter(u -> passwordEncoder.matches(...))` means "user not found" and "wrong password" both fall through to the exact same `InvalidCredentialsException`. That's deliberate, not an oversight — a login endpoint that returns a different error for "no such email" versus "wrong password" hands an attacker a free way to enumerate which emails have accounts. One generic error, always, regardless of which half was wrong.

5. **Generate the token** — this is the only class in the whole platform that signs anything:

**`user-service/src/main/java/com/ecommerce/user/security/JwtService.java`**
```java
@Component
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTtlMinutes;

    public JwtService(@Value("${security.jwt.secret}") String secret,
                       @Value("${security.jwt.access-ttl-minutes}") long accessTtlMinutes) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMinutes = accessTtlMinutes;
    }

    public String generateAccessToken(User user) {
        List<String> roles = user.getRoles().stream().map(Role::getName).toList();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(accessTtlMinutes, ChronoUnit.MINUTES)))
                .signWith(signingKey, Jwts.SIG.HS512)
                .compact();
    }

    public long accessTtlSeconds() {
        return accessTtlMinutes * 60;
    }
}
```

A JWT is three base64url-encoded segments joined by dots — `header.payload.signature`. Nothing in the header or payload is encrypted; both are just base64, trivially decodable by anyone, which is exactly why nothing sensitive (no password, no raw PII beyond what's already public to the token holder) belongs in the claims. What actually matters is the **signature** — proof the payload hasn't been altered since `user-service` signed it. Decode the payload this generates and it reads like:

```json
{
  "sub": "9c2f6e1a-...",
  "email": "admin@example.com",
  "roles": ["ADMIN"],
  "iat": 1784800000,
  "exp": 1784800900
}
```

> **Why access tokens are short-lived (15 minutes here) instead of long-lived**
>
> A JWT can't be revoked before it expires — there's no database row to delete, no server-side state at all, that's the entire point of it being self-contained. So the blast radius of a stolen access token is bounded by how long it's valid for, not by anyone's ability to react. Fifteen minutes is short enough that a compromised token is a minor incident, not an open-ended one — and short-lived access tokens are exactly why a *separate*, revocable refresh token exists: without one, a 15-minute session would mean re-entering a password every 15 minutes, which nobody would tolerate. `RefreshToken` — opaque, random, hashed with SHA-256, and *looked up in a database* on every use — is what makes revocation possible at all: delete or expire that one row and every future refresh attempt fails immediately, regardless of how long the (now-refused) refresh token itself would otherwise have remained valid.

### Validating a token — the Resource Server side

Every other service — `order-service` is the example here, but this is identical in all six — needs to answer "is this bearer token genuine, unexpired, and what can its holder do" without ever calling `user-service`. Spring Security's OAuth2 Resource Server support is built exactly for this.

6. **Add the dependency** (no `jjwt` here — this service only ever verifies, never signs):

**`order-service/build.gradle`**
```groovy
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
```

7. **Decode and verify** — a `JwtDecoder` checks the signature and expiry; a `JwtAuthenticationConverter` turns the `roles` claim into something Spring Security's `hasRole()`/`hasAnyRole()` understands:

**`order-service/src/main/java/com/ecommerce/order/security/SecurityConfig.java`**
```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder(@Value("${security.jwt.secret}") String secret) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS512).build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders/**").hasAnyRole("CUSTOMER", "MANAGER", "ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }
}
```

> **Every clause here is doing real work — none of it is boilerplate**
>
> `csrf().disable()` is correct specifically *because* this is a stateless bearer-token API with no browser-managed session cookie — CSRF is fundamentally a session-cookie attack, and there's no ambient credential here for it to hijack. `sessionCreationPolicy(STATELESS)` tells Spring Security never to create an `HttpSession` — every request carries everything needed to authenticate it (the token), so there's nothing to remember between requests. `setAuthoritiesClaimName("roles")` + `setAuthorityPrefix("ROLE_")` is the line that makes `hasRole("CUSTOMER")` below actually match the `"roles": ["CUSTOMER"]` claim from the token — Spring Security's convention is that `hasRole("X")` checks for an authority literally named `ROLE_X`, so without this converter every role check would silently fail closed.

> **Why `oauth2ResourceServer()`, not a hand-rolled `OncePerRequestFilter`**
>
> An easy, tempting first instinct is a custom servlet filter: read the header, verify the signature yourself, set the `SecurityContext`. It's not much code — and it's also exactly the kind of code that's easy to get subtly wrong. A hand-rolled filter that hits an unexpected error while parsing a malformed token can end up silently leaving the security context empty rather than actively rejecting the request — which then falls through to whatever Spring's default unauthenticated handling does, which may not be the clean `401` you intended. Spring's own resource server support routes an invalid or expired token through `BearerTokenAuthenticationEntryPoint`, which reliably produces a real `401` with a proper `WWW-Authenticate` header — battle-tested code path, not a home-grown one with an edge case nobody's found yet.

> **CRITICAL — The tradeoff this platform makes, on purpose — worth knowing, not necessarily copying**
>
> `NimbusJwtDecoder.withSecretKey(key).macAlgorithm(HS512)` is **symmetric** signing — the exact same secret both *signs* a token (in `user-service`) and *verifies* one (in every other service, from the identical `security.jwt.secret` config value). That means any of these six services, if it wanted to, could forge a token claiming to be anyone — they all hold signing-capable material, not just verification-capable material. A production system with real multi-team trust boundaries more commonly uses **asymmetric** signing (RS256/ES256): the authorization server holds a private key and signs; every resource server only ever needs the corresponding *public* key (often published at a JWKS endpoint and cached), which can verify a signature but can't produce one. A compromised resource server under that model can't mint tokens even if fully breached. HS512 here is a reasonable simplification for a platform where every service is first-party and deploys from the same trust boundary — it would not be the right choice the moment a genuinely untrusted service needed to verify tokens without also being trusted to issue them.

#### ✓ Checkpoint

```bash
# No credentials at all -> 401, not 403 -- there's a real difference (see below)
curl -i localhost:8084/api/v1/orders/00000000-0000-0000-0000-000000000000
# 401 Unauthorized

# Log in, get a real token
TOKEN=$(curl -s -X POST localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Admin@12345"}' | jq -r .accessToken)

# Decode it yourself -- it's just base64, no secret required to READ it, only to verify it
echo $TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | jq .
# {"sub":"...", "email":"admin@example.com", "roles":["ADMIN"], "iat":..., "exp":...}

# A valid token, but the ADMIN role doesn't satisfy hasRole("CUSTOMER") on POST /orders
curl -i -X POST localhost:8084/api/v1/orders -H "Authorization: Bearer $TOKEN" -d '{...}'
# 403 Forbidden

# Same token, a GET any authenticated role can reach
curl -i localhost:8084/api/v1/orders/00000000-0000-0000-0000-000000000000 -H "Authorization: Bearer $TOKEN"
# 404 Not Found -- past the security check entirely, now it's just "no such order"
```

**401 vs 403 is the whole story of what just got built**, made concrete: `401 Unauthorized` means "I don't know who you are" (no token, or a token that fails verification) — that's `oauth2ResourceServer()` talking. `403 Forbidden` means "I know exactly who you are, and the answer is still no" — that's `authorizeHttpRequests()` talking, one layer further in, using the `roles` claim the `JwtAuthenticationConverter` extracted. Two different failures, two different Spring Security components, and a client can act correctly on the difference (re-authenticate vs. don't bother retrying, this user will never be allowed). The next phase puts a gateway in front of all of this — and repeats this exact same validation there too, deliberately redundantly, since `order-service` validating its own tokens must never become optional just because something upstream already checked once.

<a id="phase5"></a>
## 05 — Gateway In Front

> **Goal:** a client talks to exactly one address. Every request gets a correlation ID, a fast-fail JWT check, rate limiting, and a real answer even when a downstream service is down — before it ever reaches order-service, inventory-service, or user-service.

### First: why this one service is built completely differently

Every service so far is a normal Spring MVC app on Tomcat — one thread per request, blocking I/O, the model you've been using since phase 1. A gateway's entire job is different in kind: it holds open a large number of client connections and, for each one, opens *another* connection to a backend service and shuffles bytes between them. Doing that with one OS thread per in-flight request scales far worse than it does for order-service, where each request does a bounded amount of real work and returns. Spring Cloud Gateway is built on **WebFlux** and Netty specifically for this — non-blocking I/O, a small fixed pool of event-loop threads handling many concurrent connections instead of one thread parked per connection.

> **This changes the code you write, not just a config flag**
>
> Every filter from here on returns `Mono<Void>`, not `void` — there is no "handle the request, then return" the way a `OncePerRequestFilter` works. A `Mono` is a promise of a value (or completion) that hasn't necessarily happened yet; you compose behavior by chaining onto it (`.then(...)`, `.map(...)`), you never block waiting for it. Blocking inside a WebFlux filter — a plain JDBC call, a synchronous HTTP client, a `Thread.sleep` — stalls one of the small number of event-loop threads and can degrade every other in-flight request sharing it, not just the one that blocked. None of the gateway's own filters below touch a database or block on I/O; that constraint is why.

1. **Dependencies** — the reactive gateway starter, a reactive Redis client for rate limiting, and `jjwt` again (the gateway verifies signatures itself, for reasons the next step explains):

**`gateway-service/build.gradle`**
```groovy
implementation 'org.springframework.cloud:spring-cloud-starter-gateway-server-webflux'
implementation 'org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j'
implementation 'org.springframework.boot:spring-boot-starter-data-redis-reactive'

implementation 'io.jsonwebtoken:jjwt-api:0.13.0'
runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.13.0'
runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.13.0'
```

2. **Routes** — path predicates decide which service a request goes to; `RewritePath` translates the public-facing URL into each service's actual API path:

**`gateway-service/src/main/resources/application.yml` (excerpt)**
```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: user-service-auth
              uri: http://localhost:8081
              predicates: [ "Path=/users/auth/**" ]
              filters:
                - "RewritePath=/users/auth/(?<segment>.*), /api/v1/auth/$\\{segment}"

            - id: order-service
              uri: http://localhost:8084
              predicates: [ "Path=/orders,/orders/**" ]
              filters:
                - "RewritePath=/orders(?<segment>/?.*), /api/v1/orders$\\{segment}"

            - id: inventory-service
              uri: http://localhost:8083
              predicates: [ "Path=/inventory,/inventory/**" ]
              filters:
                - "RewritePath=/inventory(?<segment>/?.*), /api/v1/inventory$\\{segment}"

server:
  port: 8080
```

> **Why the public path and the internal path are different at all**
>
> A client calls `POST /orders`; order-service actually exposes `POST /api/v1/orders`. The gateway is the seam where "how clients address this system" and "how each service versions and structures its own API" get to evolve independently — order-service could move to `/api/v2/orders` tomorrow and, as long as the gateway's rewrite rule is the only thing that changes, no client-facing URL moves at all.

3. **Correlation ID — first filter in the chain, deliberately:**

**`gateway-service/src/main/java/com/ecommerce/gateway/filter/CorrelationIdGlobalFilter.java`**
```java
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationIdConstants.HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(CorrelationIdConstants.HEADER, correlationId)
                .build();
        exchange.getResponse().getHeaders().add(CorrelationIdConstants.HEADER, correlationId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
```

> **Why this runs before literally everything else**
>
> `GlobalFilter`s run in an order every request passes through, controlled by `getOrder()` — lower runs first. This one claims `HIGHEST_PRECEDENCE` so every filter after it, and every downstream service, can assume a correlation ID already exists rather than each having to handle "what if nobody set one yet." Reusing an incoming ID rather than always minting a fresh one matters too: if a client (or a test harness) already generated one, this preserves it instead of quietly discarding it and breaking that caller's own tracing.

4. **A fast-fail JWT check** — reject obviously-invalid tokens before wasting a network hop to a backend service that would just reject them anyway:

**`gateway-service/src/main/java/com/ecommerce/gateway/filter/JwtAuthenticationGlobalFilter.java` (excerpt)**
```java
@Component
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/users/auth", "/actuator/health");

    private final SecretKey signingKey;

    public JwtAuthenticationGlobalFilter(@Value("${security.jwt.secret}") String secret) {
        this.signingKey = hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (PUBLIC_PREFIXES.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return unauthorized(exchange);
        }

        try {
            Claims claims = Jwts.parser().verifyWith(signingKey).build()
                    .parseSignedClaims(header.substring(7)).getPayload();

            @SuppressWarnings("unchecked")
            List<String> roles = claims.get("roles", List.class);

            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header("X-User-Id", claims.getSubject())
                    .header("X-User-Roles", String.join(",", roles))
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (JwtException e) {
            return unauthorized(exchange);
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
```

> **CRITICAL — Why a hand-rolled check here, when phase 4 specifically warned against that**
>
> Two real reasons, not a contradiction of phase 4's advice. First, mechanically: Spring Security's `oauth2ResourceServer()` DSL used in phase 4 is built for the Servlet stack this gateway deliberately isn't on — a reactive equivalent exists, but pulling in the whole reactive Spring Security filter chain for what's fundamentally a cheap pre-check is more machinery than the job needs. Second, and more important: **this check is not the security boundary** — re-read the class comment. Every downstream service still independently validates with the real `oauth2ResourceServer()` setup from phase 4, using the exact same secret. If this filter had a bug, or was skipped entirely (someone calls order-service directly, bypassing the gateway on purpose or by mistake), *nothing about the platform's actual security depends on this filter having run* — it's purely an optimization, rejecting garbage before it costs a network hop. That's the condition under which a lighter-weight, hand-rolled check is fine: when getting it wrong fails safe, because something else authoritative checks again regardless.

> **`X-User-Id` / `X-User-Roles` — convenience, not trust**
>
> These headers exist so a downstream service *could* read "who's calling" without re-parsing the JWT itself — but note that every service's own `SecurityConfig` (phase 4) never actually reads them; it re-validates the original `Authorization` header independently every time. That's deliberate: an `X-User-Id` header is just a string another actor could set to literally anything if they could reach a service directly — it carries no cryptographic proof the way the JWT's signature does. Never let a downstream trust decision hang on a header the gateway merely *added*, only on a token it independently *verified*.

5. **Log every request exactly once** — last in the chain, so `doFinally` sees the real final status code after every other filter and the proxied call have both completed:

**`gateway-service/src/main/java/com/ecommerce/gateway/filter/RequestLoggingGlobalFilter.java`**
```java
@Component
public class RequestLoggingGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        String correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationIdConstants.HEADER);
        String path = exchange.getRequest().getPath().value();

        return chain.filter(exchange).doFinally(signal -> {
            long durationMs = System.currentTimeMillis() - start;
            int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;
            log.info("correlationId={} method={} path={} status={} durationMs={}",
                    correlationId, exchange.getRequest().getMethod(), path, status, durationMs);
        });
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
```

The chain so far, in the order it actually runs: **correlation ID** (`HIGHEST_PRECEDENCE`) → **JWT check** (`HIGHEST_PRECEDENCE + 10`) → route match & proxy to the backend → **request logging** (`LOWEST_PRECEDENCE`, runs last on the way back out). Every filter in between — rate limiting, the circuit breaker, both added next — slots in between the JWT check and the proxied call, in whatever order each route declares them.

6. **Rate limiting** — a Redis-backed token bucket per route, keyed by whoever's actually asking:

**`gateway-service/src/main/java/com/ecommerce/gateway/config/RateLimiterConfig.java`**
```java
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst("X-User-Id"))
                .defaultIfEmpty("anonymous");
    }
}
```
**`gateway-service/src/main/resources/application.yml` (excerpt)**
```yaml
            - id: order-service
              uri: http://localhost:8084
              predicates: [ "Path=/orders,/orders/**" ]
              filters:
                - "RewritePath=/orders(?<segment>/?.*), /api/v1/orders$\\{segment}"
                - name: RequestRateLimiter
                  args:
                    redis-rate-limiter.replenishRate: 50
                    redis-rate-limiter.burstCapacity: 100
                    key-resolver: "#{@userKeyResolver}"
```

> **Why Redis, not an in-memory counter**
>
> An in-memory rate limiter only knows about requests *that instance* handled — the moment there's more than one gateway replica (which there will be, by phase 9), each replica would enforce its own independent limit, and the real effective limit becomes (configured limit) × (replica count), silently. Redis gives every replica a shared, consistent view of each key's remaining budget, so the limit means what it says regardless of how many gateway instances are running. `replenishRate: 50, burstCapacity: 100` is a token-bucket: tokens refill at 50/sec continuously, up to a cap of 100 banked — smooth sustained traffic passes freely, and a legitimate short burst (a user's page loading five resources at once) doesn't get needlessly throttled just for arriving together.

> **Left out here, deliberately**
>
> The real platform also has a second, much stricter, IP-keyed rate limiter specifically on the login route — because `userKeyResolver` falls back to a shared `"anonymous"` bucket for anyone without an `X-User-Id` yet, which includes every login attempt. One shared bucket for all unauthenticated traffic is a real brute-force gap, not a hypothetical one. That fix, and the real ambiguous-`KeyResolver`-bean startup crash it caused the first time it was added, is phase 7 material — a genuine bug with a genuine fix, not something to pre-empt here before the gap has been felt.

7. **A circuit breaker at the gateway, and a fallback that answers instead of hanging:**

**`gateway-service/src/main/resources/application.yml` (excerpt)**
```yaml
            - id: order-service
              # ...same route as above...
              filters:
                - name: CircuitBreaker
                  args: { name: orderServiceCB, fallbackUri: "forward:/fallback/order-service" }

resilience4j:
  circuitbreaker:
    instances:
      orderServiceCB:
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
```
**`gateway-service/src/main/java/com/ecommerce/gateway/controller/FallbackController.java`**
```java
@RestController
public class FallbackController {

    @GetMapping("/fallback/{service}")
    public ResponseEntity<Map<String, Object>> fallback(@PathVariable String service) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "title", "Service Temporarily Unavailable",
                "detail", service + " is not responding; circuit breaker is open",
                "status", 503));
    }
}
```

> **The same idea as phase 3's Feign circuit breaker, one layer up**
>
> Phase 3 protected order-service from a *slow or down inventory-service*. This protects every client of the gateway from a slow or down *order-service itself* — same failure mode (a dependency stops answering, threads/connections pile up waiting), same fix (stop trying once failures cross a threshold, fail fast instead, try again after a cooldown), applied at the next layer out. This is a pattern that recurs at every boundary in a distributed system, not a one-off.

#### ✓ Checkpoint

```bash
# Through the gateway now, not order-service's own port -- one address for everything
TOKEN=$(curl -s -X POST localhost:8080/users/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Admin@12345"}' | jq -r .accessToken)

curl -i localhost:8080/orders/00000000-0000-0000-0000-000000000000 -H "Authorization: Bearer $TOKEN"
# 404 Not Found, with an X-Correlation-Id response header you never set yourself

# Hammer the same route past its configured burst
for i in $(seq 1 120); do curl -s -o /dev/null -w "%{http_code} " localhost:8080/orders -H "Authorization: Bearer $TOKEN"; done
# a run of 200s, then 429s once the bucket empties

# Stop order-service, then call it through the gateway again
curl -i localhost:8080/orders/00000000-0000-0000-0000-000000000000 -H "Authorization: Bearer $TOKEN"
# 503 Service Temporarily Unavailable -- a real, immediate answer, not a client sitting on a hung connection
```

The platform now has one public entry point, and everything a client experiences at the edge — correlation IDs, auth rejection, rate limits, a graceful answer when something's down — is enforced consistently across every route, in one place, without order-service or inventory-service knowing or caring that any of it happened. What's still missing: order-service and payment-service (not built yet) need to tell notification-service "a payment completed" — and a synchronous call, the only tool built so far, is the wrong one for that. Phase 6 is why.

<a id="phase6"></a>
## 06 — Kafka & the Outbox

> **Goal:** `payment-service` and `notification-service` exist. Authorizing a payment publishes an event; notification-service consumes it and sends a confirmation — without either service calling the other directly, and without a crash mid-request silently losing the event.

1. **Why this isn't another Feign client.** Phase 3 added a synchronous call — order-service asks inventory-service a question and waits for the answer, because it genuinely needs it before it can respond to its own caller. `order-service`→`payment-service` is the same shape (not shown again here; it's a second `FeignClient` built exactly like `InventoryClient`, see the finished `PaymentClient` if you want to check the pattern held). But payment-service telling notification-service "send a confirmation email" is different: the customer's HTTP response doesn't need to wait for an email to be queued, and payment-service shouldn't fail the payment just because the email pipeline is briefly down. The test that decides sync vs. async: *does the caller need the answer before it can respond to its own caller?* Here, no — so this is Kafka's job, not Feign's.

> **The Kafka vocabulary you need for this phase**
>
> A **topic** (`payment-events` here) is a named, ordered log — producers append, consumers read. A topic is split into **partitions** for parallelism; records with the same key (an order ID, below) always land on the same partition, so per-order ordering is preserved even with many partitions. A **consumer group** (`notification-service`, the `groupId` below) is a set of consumer instances sharing the work of one topic — Kafka tracks each group's read position (its *offset*) independently, so notification-service and any future consumer of the same topic each get their own complete view of every record.

2. **Start Kafka** — the same single-broker KRaft setup `docker-compose.yaml` uses, no Zookeeper:

```bash
docker run -d --name kafka -p 9092:9092 apache/kafka:3.8.0 \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1
```

3. **Build payment-service** — same shape as order-service (phases 1-2) plus security (phase 4, identical `SecurityConfig`, not repeated here) plus Kafka:

**`payment-service/build.gradle` (excerpt)**
```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
    implementation 'org.springframework.kafka:spring-kafka'

    runtimeOnly 'org.postgresql:postgresql'
    implementation 'org.springframework.boot:spring-boot-starter-flyway'
}
```
**`payment-service/src/main/resources/db/migration/V1__init_payments.sql` (excerpt)**
```sql
CREATE TABLE payments (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     UUID NOT NULL,
    customer_id  UUID NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    amount       NUMERIC(12,2) NOT NULL,
    method       VARCHAR(30) NOT NULL DEFAULT 'CARD',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_payments_order UNIQUE (order_id),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING','AUTHORIZED','CAPTURED','FAILED','REFUNDED'))
);
```

> **Left out here, deliberately**
>
> No `idempotency_key` column yet — retry-safe payment authorization is a phase 7 concern (a network blip making a client retry `POST /payments/authorize` shouldn't authorize the same payment twice). This phase's version can be safely re-run against a clean database as many times as you like; it just isn't safe against an actual duplicate client request yet.

4. **The naive version first** — publish directly inside the same method that saves the payment, because it looks like the obviously correct place to do it:

**`payment-service/src/main/java/com/ecommerce/payment/service/impl/PaymentServiceImpl.java` (first pass — not the real file)**
```java
@Transactional
public PaymentAuthorizationResponse authorize(AuthorizePaymentRequest request) {
    Payment payment = Payment.create(request.orderId(), request.customerId(), request.amount());
    payment.setStatus(PaymentStatus.AUTHORIZED);
    Payment saved = paymentRepository.save(payment);

    var event = new PaymentCompletedEvent(saved.getId(), saved.getOrderId(), saved.getAmount(), Instant.now());
    kafkaTemplate.send("payment-events", saved.getOrderId().toString(), objectMapper.writeValueAsString(event));

    return toResponse(saved);
}
```

> **CRITICAL — Why this is a real bug, not a style nitpick**
>
> Postgres and Kafka are two separate systems; `@Transactional` only reaches the first one. Two concrete ways this drops an event on the floor: **(1)** the pod is killed — `OOMKilled`, a node eviction, a rolling deploy — in the gap between `paymentRepository.save()` committing and `kafkaTemplate.send()` completing. The payment is `AUTHORIZED` in the database; the event never left the JVM's memory. **(2)** no crash at all: `KafkaTemplate.send()` returns a `CompletableFuture` — nothing here calls `.get()` or attaches a callback, so a broker-side failure (a leader election mid-flight, an under-replicated partition) fails that future silently while the HTTP response to the client still says success. Either way: the customer was charged, the database says so, and notification-service never finds out. No exception, no error log, no retry — it just vanishes.

5. **The fix: don't write to two systems at once — write to one, and let a separate step relay to the other.** Add an outbox table to payment-service's own database. Writing to it is just another INSERT in the same transaction as the payment, so Postgres's own atomicity guarantee — both rows commit, or neither does — now covers the whole thing:

**`payment-service/src/main/resources/db/migration/V1__init_payments.sql` (addition)**
```sql
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id   UUID NOT NULL,
    event_type     VARCHAR(50) NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    payload        JSONB NOT NULL,
    correlation_id VARCHAR(255),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
```
**`payment-service/src/main/java/com/ecommerce/payment/entity/OutboxEvent.java`**
```java
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    // Captured from MDC at write time, not read again from MDC at publish time — the publisher
    // below runs on a scheduler thread with no relation to the HTTP request that created this
    // row, so persisting it here is the only way to carry it across that gap.
    @Column(name = "correlation_id")
    private String correlationId;

    public static OutboxEvent of(String aggregateType, UUID aggregateId, String eventType, String topic,
                                  String payload, String correlationId) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.topic = topic;
        event.payload = payload;
        event.correlationId = correlationId;
        return event;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
```
**`payment-service/src/main/java/com/ecommerce/payment/event/PaymentEventProducer.java`**
```java
@Component
@RequiredArgsConstructor
public class PaymentEventProducer {

    private static final String TOPIC = "payment-events";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void publishCompleted(Payment payment) {
        var event = new PaymentCompletedEvent(payment.getId(), payment.getOrderId(), payment.getAmount(), Instant.now());
        outboxEventRepository.save(OutboxEvent.of("Payment", payment.getOrderId(), "PaymentCompletedEvent", TOPIC,
                objectMapper.writeValueAsString(event), MDC.get(CorrelationIdConstants.MDC_KEY)));
    }
}
```

6. **Wire it into the same transactional method** — the only change from the naive version is what gets called; the transaction boundary doesn't move:

**`payment-service/src/main/java/com/ecommerce/payment/service/impl/PaymentServiceImpl.java` (excerpt)**
```java
@Transactional
public PaymentAuthorizationResponse authorize(AuthorizePaymentRequest request) {
    Payment payment = Payment.create(request.orderId(), request.customerId(), request.amount());
    payment.setStatus(PaymentStatus.AUTHORIZED);
    Payment saved = paymentRepository.save(payment);

    paymentEventProducer.publishCompleted(saved);   // writes a row, in this same transaction — no Kafka call here

    return toResponse(saved);
}
```

7. **Something still has to talk to Kafka** — a scheduled poller, completely decoupled from the HTTP request that created the row:

**`payment-service/src/main/java/com/ecommerce/payment/event/OutboxPublisher.java`**
```java
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> outboxKafkaTemplate;

    @Scheduled(fixedDelay = 500)
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            try {
                var record = new ProducerRecord<>(event.getTopic(), null, event.getAggregateId().toString(), event.getPayload());
                if (event.getCorrelationId() != null) {
                    record.headers().add(CorrelationIdConstants.HEADER, event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
                }
                outboxKafkaTemplate.send(record).get();
                event.markPublished();
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("Failed to publish outbox event {} — will retry next poll", event.getId(), e);
            }
        }
    }
}
```

> **Why polling beats the naive version, concretely**
>
> The dual-write problem was fundamentally about writing to *two* systems as if they shared a transaction, when they don't. This reduces it to one: the HTTP request only ever writes to Postgres, and Postgres's own transaction guarantee is real and already trusted everywhere else in this codebase. The publisher is a *separate*, safely-retriable operation — if it crashes mid-batch, unpublished rows are still unpublished, and the next poll 500ms later just tries them again. The one new failure mode this introduces — the same row getting published twice, if `.get()` succeeds but the process dies before `event.markPublished()` commits — is a strictly easier problem than the one it replaced: *at-least-once* delivery, which a consumer can defend against by being idempotent. A silently-lost event has no such defense. Consumer idempotency is exactly what phase 7 adds.

> **Why `outboxKafkaTemplate.send(record).get()`, not fire-and-forget**
>
> This is the opposite tradeoff from step 4's bug: here, blocking on `.get()` inside a background scheduled method is exactly the right call, because there's no HTTP caller waiting on this thread — the only cost of blocking is this poller running slightly less often, and the payoff is knowing definitively whether the send succeeded before deciding to mark the row published.

8. **Build notification-service** — same shape again, this time as a *consumer*. Schema first:

**`notification-service/src/main/resources/db/migration/V1__init_notifications.sql` (excerpt)**
```sql
CREATE TABLE notification_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type  VARCHAR(50) NOT NULL,
    source_id   UUID NOT NULL,
    payload     JSONB NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_events_status CHECK (status IN ('RECEIVED','PROCESSED','FAILED'))
);

CREATE TABLE email_logs (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id     UUID REFERENCES notification_events(id) ON DELETE SET NULL,
    recipient    VARCHAR(255) NOT NULL,
    subject      VARCHAR(255) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    sent_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

> **Left out here, deliberately**
>
> No `UNIQUE (event_type, source_id)` constraint on `notification_events` yet, and the consumer below doesn't check for one. Kafka only promises *at-least-once* delivery — a rebalance or consumer restart can redeliver a record this service already processed, and right now nothing stops it from sending the same email twice. That dedup check is one of phase 7's fixes, tied directly to the "easier problem" the previous note named.

9. **The consumer side of Kafka needs its own configuration** — a deserializer, and a listener container factory to register the `@KafkaListener` method against:

**`notification-service/src/main/java/com/ecommerce/notification/config/KafkaConsumerConfig.java` (first pass — no error handling yet)**
```java
@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, PaymentCompletedEvent> paymentEventConsumerFactory() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "notification-service",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, PaymentCompletedEvent.class.getName(),
                JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.ecommerce.common.event");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentCompletedEvent> paymentEventListenerFactory(
            ConsumerFactory<String, PaymentCompletedEvent> paymentEventConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, PaymentCompletedEvent>();
        factory.setConsumerFactory(paymentEventConsumerFactory);
        return factory;
    }
}
```
**`notification-service/src/main/java/com/ecommerce/notification/event/PaymentEventConsumer.java` (first pass — no dedup yet)**
```java
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final EmailService emailService;
    private final NotificationEventRepository notificationEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-events", groupId = "notification-service",
            containerFactory = "paymentEventListenerFactory")
    @Transactional
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        NotificationEvent record = NotificationEvent.receive(
                "payment.completed", event.orderId(), objectMapper.writeValueAsString(event));
        notificationEventRepository.save(record);
        emailService.sendPaymentConfirmation(record.getId(), event.orderId(), event.amount());
    }
}
```
**`notification-service/src/main/java/com/ecommerce/notification/service/impl/EmailServiceImpl.java` (excerpt)**
```java
private void send(UUID eventId, String recipient, String subject) {
    EmailLog emailLog = EmailLog.queue(eventId, recipient, subject);
    try {
        // Real SMTP/provider dispatch happens here (spring-boot-starter-mail JavaMailSender).
        log.info("Sending email to {}: {}", recipient, subject);
        emailLog.markSent();
    } catch (Exception e) {
        log.error("Failed to send email to {}", recipient, e);
        emailLog.markFailed();
    }
    emailLogRepository.save(emailLog);
}
```

> **Why this reference implementation logs instead of sending real mail**
>
> The point of this phase is proving the Kafka pipeline end to end — payment authorized, event produced, event consumed, side effect happened — without a real SMTP account being a prerequisite for following along. `spring-boot-starter-mail` is already a dependency; swapping the `log.info` line for a real `JavaMailSender.send()` call is the only change a production deployment needs here.

#### ✓ Checkpoint

```bash
TOKEN=$(curl -s -X POST localhost:8080/users/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Admin@12345"}' | jq -r .accessToken)

# payment-service's own port -- same local-debugging pattern as every earlier phase.
# The same JWT from phase 4 works here: payment-service validates it independently.
curl -s -X POST localhost:8085/payments/authorize -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"11111111-1111-1111-1111-111111111111","customerId":"22222222-2222-2222-2222-222222222222","amount":49.99}'
# {"authorized":true,...}

# The outbox row exists immediately, published within ~500ms
docker exec -it payments-db psql -U payments_user -d payments_db \
  -c "SELECT event_type, published_at FROM outbox_events ORDER BY created_at DESC LIMIT 1;"
#     event_type      |         published_at
# ----------------------+-------------------------------
#  PaymentCompletedEvent | 2026-07-23 09:14:22.118+00

# notification-service's log shows the consumer picked it up independently -- no
# call from payment-service told it to; it found out from Kafka.
docker logs notification-service --tail 5
# Sending email to 11111111-1111-1111-1111-111111111111@customers.company.com: Payment received for order ...
```

Kill notification-service *before* authorizing a payment, wait past the outbox publisher's 500ms cycle, then start notification-service back up: the email still goes out, because it was reading a durable Kafka log, not waiting on a live connection to payment-service. That's the payoff of async messaging that a synchronous Feign call (phase 3) can't give you — full decoupling in *time*, not just in process. What's still open: nothing yet stops this consumer from processing the same event twice if Kafka redelivers it — a rebalance, a consumer restart, the outbox publisher's own at-least-once retry from the note above. Phase 7 closes that gap, plus a dead-letter topic for a record that keeps failing, real idempotency keys on payment authorization, the account lockout and rate-limiter fixes this platform actually shipped, and the circuit-breaker window-sizing bug those fixes uncovered along the way.

<a id="phase7"></a>
## 07 — Hardening It

> **Goal:** close every gap the last three phases named and deliberately left open. Five fixes, each with a real failure mode behind it — not defensive programming for its own sake.

### 1. Idempotency keys — retry-safe writes on `order-service` and `payment-service`

Phase 1's `orders` table and phase 6's `payments` table both left this column out on purpose. The gap: a client's `POST /orders` times out on the network, not on the server — the order was actually created, but the client, having seen no response, retries the exact same request. Without something to recognize "I've seen this before," that's a second order, a second payment authorization, a second charge.

**`order-service/src/main/resources/db/migration` (addition)**
```sql
ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(255);
-- Partial index: nullable, so the (much more common) request with no key at all is never
-- compared against anything -- only two requests that both supplied the *same* key collide.
CREATE UNIQUE INDEX uq_orders_idempotency_key ON orders (idempotency_key) WHERE idempotency_key IS NOT NULL;
```
**`order-service/src/main/java/com/ecommerce/order/service/impl/OrderServiceImpl.java` (excerpt)**
```java
@Transactional
public IdempotentResult<OrderResponse> createOrder(OrderRequest request, String idempotencyKey) {
    if (idempotencyKey != null) {
        var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return IdempotentResult.replayed(orderMapper.toResponse(existing.get()));
        }
    }

    var items = request.items().stream().map(this::reserveAndPrice).toList();
    BigDecimal total = items.stream().map(OrderItem::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    Order order = Order.create(request.customerId(), items, total, idempotencyKey);

    Order saved;
    try {
        // saveAndFlush, not save -- a duplicate-key violation needs to surface here, inside
        // this try, not silently at end-of-transaction flush where this catch is out of scope.
        saved = orderRepository.saveAndFlush(order);
    } catch (DataIntegrityViolationException e) {
        if (idempotencyKey == null) throw e;
        // Lost a race to a concurrent request carrying the same key. The reservations this
        // attempt already made above are an accepted, narrow leak for that rare case.
        Order winner = orderRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
        return IdempotentResult.replayed(orderMapper.toResponse(winner));
    }

    // ...authorize payment, confirm, publish -- same as phase 3/6, unchanged.
    return IdempotentResult.created(orderMapper.toResponse(saved));
}
```
**`order-service/src/main/java/com/ecommerce/order/controller/OrderController.java` (excerpt)**
```java
@PostMapping
public ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    var result = orderService.createOrder(request, idempotencyKey);
    // 201 for a genuinely new order; 200 for a replayed Idempotency-Key -- so a client can
    // tell "this just happened" from "you already did this" without parsing the body.
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
}
```

> **Why check-then-catch, not just check**
>
> The check at the top (`findByIdempotencyKey`) is an optimization, not the safety guarantee — two requests carrying the same key can both pass that check before either has committed. The unique index is the real guarantee; the `try`/`catch` is what turns that database-level rejection back into the same successful-looking replay response the client would have gotten if it had won the race. `payment-service`'s `PaymentServiceImpl.authorize` got the identical treatment — same partial index on `payments.idempotency_key`, same check-then-catch shape.

### 2. Consumer idempotency and a DLQ — `notification-service`

Phase 6's checkpoint named this gap directly: Kafka only promises *at-least-once* delivery, and the naive consumer had no defense against a redelivered record. Two fixes, same root cause (a rebalance or restart can replay a record already handled):

**`notification-service/src/main/resources/db/migration` (addition)**
```sql
ALTER TABLE notification_events
    ADD CONSTRAINT uq_notification_events_type_source UNIQUE (event_type, source_id);
```
**`notification-service/src/main/java/com/ecommerce/notification/event/PaymentEventConsumer.java` (excerpt)**
```java
if (notificationEventRepository.existsByEventTypeAndSourceId(EVENT_TYPE, event.orderId())) {
    log.info("Skipping duplicate {} event for order {} -- already processed", EVENT_TYPE, event.orderId());
    return;
}
NotificationEvent record = NotificationEvent.receive(EVENT_TYPE, event.orderId(), objectMapper.writeValueAsString(event));
try {
    // saveAndFlush + catch, same shape as #1 above -- closes the race where two redeliveries
    // both pass the existsBy check before either persists.
    notificationEventRepository.saveAndFlush(record);
} catch (DataIntegrityViolationException e) {
    log.info("Duplicate {} event for order {} raced onto the unique constraint -- already processed", EVENT_TYPE, event.orderId());
    return;
}
emailService.sendPaymentConfirmation(record.getId(), event.orderId(), event.amount());
```

The second fix is unrelated to duplication — it's what happens when a record isn't a duplicate, but genuinely can't be processed (a bug, a bad payload). Before this, the listener container's default behavior was to retry the same record forever, never advancing its offset, silently stalling the whole partition. This bounds it:

**`notification-service/src/main/java/com/ecommerce/notification/config/KafkaConsumerConfig.java` (addition)**
```java
@Bean
public ProducerFactory<String, Object> dlqProducerFactory() {
    Map<String, Object> props = Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
    return new DefaultKafkaProducerFactory<>(props);
}

@Bean
public KafkaTemplate<String, Object> dlqKafkaTemplate(ProducerFactory<String, Object> dlqProducerFactory) {
    return new KafkaTemplate<>(dlqProducerFactory);
}

// 3 retries, 1s apart, then the record is published to payment-events.DLT and the offset is
// committed -- the consumer moves on instead of stalling the partition forever.
@Bean
public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> dlqKafkaTemplate) {
    var recoverer = new DeadLetterPublishingRecoverer(dlqKafkaTemplate);
    return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
}
```

> **Wired onto the listener container factory**
>
> `factory.setCommonErrorHandler(kafkaErrorHandler)` is the only change to the `ConcurrentKafkaListenerContainerFactory` bean from phase 6 — everything else about registering the `@KafkaListener` method stays the same. A deserialization failure (malformed JSON) is handled separately by `ErrorHandlingDeserializer`, already in place since phase 6; this error handler only ever sees exceptions the listener method itself throws.

### 3. Account lockout — `user-service`

Phase 4's `users` table had no lockout columns, and `login` had no defense past "check the password." Five failed attempts locks the account for 15 minutes:

**`user-service/src/main/resources/db/migration` (addition)**
```sql
ALTER TABLE users ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until TIMESTAMPTZ;
```
**`user-service/src/main/java/com/ecommerce/user/entity/User.java` (addition)**
```java
private static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
private static final long LOCKOUT_MINUTES = 15;

public boolean isLocked() {
    return lockedUntil != null && lockedUntil.isAfter(Instant.now());
}

public void recordFailedLogin() {
    failedLoginAttempts++;
    if (failedLoginAttempts >= MAX_FAILED_LOGIN_ATTEMPTS) {
        lockedUntil = Instant.now().plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES);
    }
}

public void recordSuccessfulLogin() {
    failedLoginAttempts = 0;
    lockedUntil = null;
}
```
**`user-service/src/main/java/com/ecommerce/user/service/impl/AuthServiceImpl.java` (excerpt)**
```java
@Transactional
public LoginResponse login(LoginRequest request) {
    User user = userRepository.findByEmail(request.email()).orElseThrow(InvalidCredentialsException::new);

    // Checked before the password comparison so a locked-out account can't keep being probed
    // for free. This does reveal the account is locked (distinguishable from "wrong
    // password") -- an accepted tradeoff; the alternative tells a legitimately locked-out
    // user nothing about why they can't log in.
    if (user.isLocked()) {
        throw new AccountLockedException();
    }

    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
        user.recordFailedLogin();
        userRepository.save(user);
        throw new InvalidCredentialsException();
    }

    user.recordSuccessfulLogin();
    userRepository.save(user);
    String accessToken = jwtService.generateAccessToken(user);
    String refreshToken = issueRefreshToken(user.getId());
    return new LoginResponse(accessToken, refreshToken, jwtService.accessTtlSeconds());
}
```

> **One enumeration tradeoff, made consistently**
>
> Phase 4 already accepted that "wrong password" and "no such account" return the identical `InvalidCredentialsException` so a caller can't enumerate valid emails from the login endpoint. Locking reopens a narrower version of the same question — a `423 Locked` response does confirm the account exists. This codebase makes the same call OWASP's Authentication guidance makes: that narrow leak is worth it, because the alternative (a locked-out real user gets the same opaque error as a wrong password, forever, with no path to understanding why) is worse for legitimate users than it is protective against attackers, who already know whether the account they're attacking exists.

### 4. Rate limiting fix — the ambiguous `KeyResolver` bean, gateway-service

This is a real bug this build hit, not a hypothetical: phase 5's `RateLimiterConfig` shipped with a single `userKeyResolver` bean. Adding a second one for the login route below crashed the application context at startup — `RequestRateLimiterGatewayFilterFactory` autowires a default `KeyResolver` for any route that doesn't name one explicitly, and with two candidate beans and no way to pick, that autowiring is ambiguous.

**`gateway-service/src/main/java/com/ecommerce/gateway/config/RateLimiterConfig.java`**
```java
@Bean
@Primary   // <-- the fix. Every route below names its resolver explicitly, so this default
           // is never actually used -- but the bean still has to exist and be unambiguous.
public KeyResolver userKeyResolver() {
    return exchange -> Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst("X-User-Id"))
            .defaultIfEmpty("anonymous");
}

// Used only by the login route below, where there's no X-User-Id yet -- every unauthenticated
// caller would otherwise share one "anonymous" bucket, so one credential-stuffing script
// could exhaust everyone else's login attempts. Per-IP gives each caller their own bucket.
@Bean
public KeyResolver ipKeyResolver() {
    return exchange -> Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
            .map(addr -> addr.getAddress().getHostAddress())
            .defaultIfEmpty("unknown");
}
```
**`gateway-service/src/main/resources/application.yml` (excerpt)**
```yaml
            - id: user-service-auth
              uri: http://user-service
              predicates: [ "Path=/users/auth/**" ]
              filters:
                - "RewritePath=/users/auth/(?<segment>.*), /api/v1/auth/$\\{segment}"
                - name: RequestRateLimiter
                  args:
                    redis-rate-limiter.replenishRate: 2
                    redis-rate-limiter.burstCapacity: 5
                    key-resolver: "#{@ipKeyResolver}"
```

> **CRITICAL — Two independent layers, on purpose**
>
> This and fix #3 look redundant — both defend login — but they stop different attacks. The IP-keyed limiter (2/s, burst 5) stops a single script hammering the endpoint, before it ever reaches a real password check. Account lockout stops a *distributed* credential-stuffing attack — many IPs, each staying under the rate limit, all guessing passwords for one target account — which the rate limiter alone can't see because no single IP ever crosses its threshold. Defense in depth means each layer covers the other's blind spot, not that either one is redundant.

### 5. Circuit breaker window-sizing — gateway-service

Phase 5's circuit breaker excerpt showed `sliding-window-size`, `failure-rate-threshold`, and `wait-duration-in-open-state` per route and left it there. Two more settings default to values (100 and 10) sized for a much larger window than the 20 every instance here actually uses:

**`gateway-service/src/main/resources/application.yml` (addition)**
```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        minimum-number-of-calls: 10
        permitted-number-of-calls-in-half-open-state: 5
    instances:
      orderServiceCB: { sliding-window-size: 20, failure-rate-threshold: 50, wait-duration-in-open-state: 10s }
      # ...one instance per route, same shape...
```

> **CRITICAL — The symptom that gave this away**
>
> With `minimum-number-of-calls` left at its default of 100, a 20-slot `COUNT_BASED` window can *never* hold enough calls to satisfy it — the failure rate is never considered statistically valid, no matter what it actually is. Under bursty load this leaves a breaker flapping between `OPEN` and `HALF_OPEN` indefinitely, permanently short-circuiting real traffic even once the backing service is fully healthy again — the opposite of what a circuit breaker is for. Both settings need to fit inside the smaller window explicitly; sizing only `failure-rate-threshold`/`wait-duration-in-open-state` and assuming the rest inherits sensibly is exactly the mistake that shipped here first.

#### ✓ Checkpoint

```bash
# 1 -- Idempotency: same key, same order, twice
KEY=$(uuidgen)
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/orders -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d '{...}'
# 201
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/orders -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d '{...}'
# 200 -- same order, not a second one

# 2 -- Login rate limit: burst past 5 in under a second
for i in $(seq 1 8); do curl -s -o /dev/null -w "%{http_code} " localhost:8080/users/auth/login \
  -H 'Content-Type: application/json' -d '{"email":"x@example.com","password":"wrong"}'; done
# 401 401 401 401 401 429 429 429 -- the limiter trips before the 5th genuine attempt even lands

# 3 -- Account lockout: space attempts out past the rate limit, fail 5 times for real
for i in $(seq 1 5); do curl -s -X POST localhost:8080/users/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"wrong"}' -o /dev/null -w "%{http_code}\n"; sleep 1; done
curl -s -X POST localhost:8080/users/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Admin@12345"}' -w "\n%{http_code}\n"
# 423 -- locked out even with the *correct* password now, until locked_until passes
```

The DLQ and circuit-breaker fixes are harder to prove with a one-line curl — you'd watch `payment-events.DLT` fill via a console consumer for the first, or watch a breaker's state transitions in the Grafana dashboards phase 10 wires up for the second. Both are real fixes for real failure modes this build hit, not hardening added because a checklist called for it. What's left: everything from here down is *how this already-correct application actually ships* — a Dockerfile, a Kubernetes manifest and Helm chart, the observability stack that makes the DLQ and circuit-breaker state actually visible, and a load test that proves the tuning under real concurrency.

<a id="phase8"></a>
## 08 — Containerize

> **Goal:** every service builds into a small, non-root, container-aware runtime image, and `docker compose up` brings up the whole seven-service system plus Postgres, Kafka, and Redis — the exact same services this tutorial built one at a time, phases 1 through 7, now wired together with one command.

1. **The Dockerfile** — two `FROM` stages, not one. Every service in this platform uses the identical shape; here it is for `order-service`:

**`order-service/Dockerfile`**
```dockerfile
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY gradlew build.gradle ./
COPY gradle ./gradle
COPY common ./common
COPY order-service ./order-service
# The root settings.gradle includes all 8 modules, but this build context only has
# common/ and order-service/ -- regenerate a settings.gradle scoped to just what's actually
# present, otherwise Gradle fails trying to configure sibling modules with no directory.
RUN cat > settings.gradle <<'GRADLE_EOF' && \
    chmod +x gradlew && ./gradlew :order-service:bootJar -x test --no-daemon
plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}
rootProject.name = 'ecommerce-platform'
include 'common'
include 'order-service'
GRADLE_EOF

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /app/order-service/build/libs/*.jar app.jar
USER spring:spring
EXPOSE 8084
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "--sun-misc-unsafe-memory-access=allow", "-jar", "app.jar"]
```

> **Why two stages, not one**
>
> The first stage needs a full JDK, Gradle's own dependency cache, and every source file — none of which the running application needs, all of which is attack surface and image weight if it ships. `COPY --from=build` pulls across exactly one artifact, the built jar, into a fresh image that starts from a JRE (not a JDK — nothing here ever compiles anything at runtime). The build stage is discarded entirely; only the second `FROM` becomes the image that actually runs.

> **The `settings.gradle` trick, and why it's necessary**
>
> This is a multi-module Gradle build — one root `settings.gradle` lists all 8 modules, and Gradle needs every listed module's directory to actually exist to configure the build at all. But the Dockerfile only `COPY`s `common/` and `order-service/` into the image — copying all 8 modules just to build one would make every service's image rebuild on every other service's code change, defeating Docker's layer cache entirely. The fix: a heredoc regenerates `settings.gradle` inside the build stage, scoped to only the two modules actually present, before `gradlew` ever runs. Small trick, but the alternative (an unscoped multi-module build in every single-service image) would make the whole containerization step far slower than it needs to be.

> **Non-root, deliberately**
>
> `addgroup -S spring && adduser -S spring -G spring` then `USER spring:spring` means the JVM inside this container never runs as root. This doesn't harden the application code itself — it limits the blast radius *if* something inside the container is ever compromised: a process running as an unprivileged user can't write outside what it's explicitly been given access to, can't bind low ports, and fails several classes of container-escape technique that assume root. Kubernetes' own Pod Security Standards (phase 9) can enforce this as policy, not just convention — but the image has to actually not run as root first.

> **CRITICAL — The two flags after `-jar` that aren't decoration**
>
> `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75` tells the JVM to size its heap as a percentage of the *container's* cgroup memory limit, not the host machine's total RAM — without it, a JVM in a container capped at 512Mi can still see the host's 32GB and size its heap accordingly, then get OOMKilled the moment it actually tries to use what it thinks it has. This is exactly the failure mode [docs/performance-baseline.html](performance-baseline.md) caught live: `gateway-service` OOMKilled under peak load in Kubernetes specifically because of a requests/limits mismatch these flags don't fully paper over — sizing the container's own memory limit correctly is still phase 9's job, but this flag is what makes the JVM honest about whatever limit it's given. `--sun-misc-unsafe-memory-access=allow` is unrelated: recent JDKs increasingly restrict reflective access to `sun.misc.Unsafe` by default, and a transitive dependency here (Netty, underneath both `reactor-netty` on the gateway and the Kafka client on every other service) still reaches into it for off-heap buffer performance tricks. This flag opts back into allowing that access rather than the JVM refusing to start.

> **No `.dockerignore` in this repo — and why that's fine here**
>
> A `.dockerignore` exists to stop `COPY . .` from dragging `.git/`, build output, or stray secrets into the image. This Dockerfile never does a blanket `COPY . .` — every line names exactly the files and directories it needs (`gradlew`, `build.gradle`, `gradle/`, `common/`, and the one service directory). The allow-list *is* the ignore-list, just inverted; nothing not explicitly named ever enters the build context.

2. **Wire it into `docker-compose.yaml`** — a Postgres instance per service (database-per-service, unchanged since phase 1), Kafka and Redis once each, then the services themselves:

**`docker-compose.yaml` (excerpt)**
```yaml
services:
  postgres-orders:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: orders_db
      POSTGRES_USER: orders_user
      POSTGRES_PASSWORD: orders_pass
    ports: ["5444:5432"]
    volumes: ["pg-orders:/var/lib/postgresql/data"]

  kafka:
    image: apache/kafka:3.8.0
    ports: ["9092:9092"]
    # ...KRaft single-broker config, same as phase 6's docker run...

  redis:
    image: redis:7-alpine

  order-service:
    build: { context: ., dockerfile: order-service/Dockerfile }
    depends_on: [postgres-orders, kafka, inventory-service, payment-service]
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DB_PASSWORD: orders_pass
      JWT_SECRET: local-dev-secret-do-not-use-in-production-0123456789-extended-for-hs512
    ports: ["8084:8084"]

  gateway-service:
    build: { context: ., dockerfile: gateway-service/Dockerfile }
    depends_on: [user-service, product-service, inventory-service, order-service, payment-service, redis]
    environment:
      SPRING_PROFILES_ACTIVE: docker
      REDIS_HOST: redis
      JWT_SECRET: local-dev-secret-do-not-use-in-production-0123456789-extended-for-hs512
    ports: ["8080:8080"]
```

> **Why `build: { context: ., dockerfile: order-service/Dockerfile }`, not a context scoped to the service directory**
>
> The build context has to be the repo root, not `order-service/`, because the Dockerfile's `COPY common ./common` line needs `common/` to be reachable from wherever the build context starts — and `common/` lives as a sibling of `order-service/`, not inside it. Building a single image standalone outside Compose needs the same rule: `docker build -f order-service/Dockerfile -t order-service:local .`, run from the repo root, not from inside `order-service/`.

> **What `depends_on` here does *not* guarantee**
>
> Without a `condition: service_healthy` clause (which needs a `healthcheck:` block this compose file doesn't define), `depends_on` only waits for `postgres-orders`'s container *process to start* — not for Postgres to actually be accepting connections yet. In practice this works locally because Postgres becomes ready in a second or two, while Spring Boot's own startup (classpath scanning, Flyway migrating, the JPA metamodel building) reliably takes longer — order-service is very unlikely to attempt its first connection before Postgres is listening. That's a real simplification, not a guarantee: it's exactly the gap Kubernetes readiness/liveness probes (phase 9) close properly, by asking the application itself "are you actually ready?" instead of inferring it from container start order.

#### ✓ Checkpoint

```bash
docker compose up --build
# ...all seven services plus six Postgres instances, Kafka, Redis, and the observability
# stack come up; this takes a few minutes the first time, then reuses Docker's layer cache

curl -s localhost:8084/actuator/health
# {"status":"UP"}

# Confirm it's actually non-root inside the running container
docker exec order-service whoami
# spring

# Images stay small -- no build toolchain shipped in the runtime layer
docker images | grep order-service
# order-service    latest    ...    ~280MB

# Full system, same login/order flow every checkpoint since phase 4 has used, now against
# containers instead of a bare Postgres and `./gradlew bootRun`
TOKEN=$(curl -s -X POST localhost:8080/users/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Admin@12345"}' | jq -r .accessToken)
curl -i localhost:8080/orders -H "Authorization: Bearer $TOKEN"
```

Every phase before this one ran services with `./gradlew bootRun` against infrastructure started by hand (a bare `docker run postgres`, a bare `docker run kafka`). Nothing about the application changed to get here — the same jars, the same Flyway migrations, the same JWT secret shape. What changed is packaging: a reproducible, portable artifact that runs identically on a laptop or a cluster. That portability is the whole point of phase 9 — the same images, unchanged, become Kubernetes Pods next.

<a id="phase9"></a>
## 09 — Kubernetes & Helm

> **Goal:** `order-service`'s image from phase 8 runs as a Kubernetes Deployment with real health probes and autoscaling, deployed via a Helm chart — and the resource limits it runs with come from an actual load test, not a guess. This phase focuses on *what these manifests are and why they're shaped this way*; the exact command sequence to stand the whole platform up locally lives in [docs/local-deployment.html](local-deployment.md), not repeated here.

1. **No service-registry code was ever written, on purpose.** No Eureka server, no Consul agent, nothing in any `build.gradle` for one. Phase 8's `docker` profile already used plain hostnames (`http://user-service`) — Docker Compose's embedded DNS resolves a service name to whichever container is running it. Kubernetes does the identical job at cluster scale: every `Service` object gets a DNS name, cluster-wide, automatically. The `kubernetes` Spring profile just extends the same idea to a fully-qualified form for cross-namespace calls:

**`payment-service/src/main/resources/application.yml` (kubernetes profile, excerpt)**
```yaml
spring:
  config:
    activate:
      on-profile: kubernetes
  datasource:
    url: jdbc:postgresql://postgres-payments.data.svc.cluster.local:5432/payments_db
  kafka:
    bootstrap-servers: kafka-broker-0.kafka.data.svc.cluster.local:9092,...
```

> **`<service>.<namespace>.svc.cluster.local`, decoded**
>
> This platform's Postgres StatefulSets live in a separate `data` namespace from the services in `ecommerce` (see step 4) — a same-namespace call could just say `postgres-payments`, but crossing a namespace boundary needs the fully-qualified form. This is the whole story: no registration call, no heartbeat, no client-side service cache to keep warm. A pod asks cluster DNS "where is `postgres-payments.data` right now" on every connection, and gets a live answer. See [architecture.html Part 5](architecture.md#part5) for the deeper reference version of this same design choice.

2. **The Deployment** — this is what actually replaces phase 8's `docker compose up`. Templated with Helm (`{{ }}` placeholders filled from `values.yaml`), but every concrete value below is exactly what order-service runs with today:

**`helm/ecommerce-platform/charts/order-service/templates/deployment.yaml` (excerpt)**
```yaml
spec:
  replicas: {{ .Values.replicaCount }}
  template:
    metadata:
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "{{ .Values.containerPort }}"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      containers:
        - name: {{ include "order-service.fullname" . }}
          image: "{{ .Values.global.imageRegistry }}/{{ .Values.image.repository }}:{{ .Values.global.imageTag | default .Values.image.tag }}"
          envFrom:
            - configMapRef: { name: {{ include "order-service.fullname" . }}-config }
            - secretRef: { name: {{ include "order-service.fullname" . }}-secret }
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          startupProbe:
            httpGet: { path: /actuator/health, port: {{ .Values.containerPort }} }
            failureThreshold: 30
            periodSeconds: 5
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: {{ .Values.containerPort }} }
            periodSeconds: 10
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: {{ .Values.containerPort }} }
            periodSeconds: 15
            failureThreshold: 3
```

> **CRITICAL — Three probes, three different jobs — this is phase 8's promised fix**
>
> Phase 8 flagged that `depends_on` only knows container *start* order, not readiness, and promised Kubernetes would close that gap properly. Here's how: **startupProbe** gives a slow-starting JVM (Flyway migrating, the Spring context initializing) up to 30×5s=150s before anything else even starts checking — without it, a slower boot than expected gets killed as if it were hung. Once startup succeeds, **readinessProbe** takes over: while it's failing, Kubernetes removes the pod from the Service's routing rotation entirely — no traffic reaches a pod that isn't ready, which is the actual fix for phase 8's gap, not container start order. **livenessProbe** is different again: it decides whether to *restart* the pod outright, for a process that's still running but has become permanently stuck (a deadlock, an unrecoverable connection pool exhaustion) — 3 consecutive failures 15s apart before Kubernetes gives up and restarts it.

3. **Config, secrets, and the Service** — the same jar from phase 8, unchanged; only what's injected around it differs by environment:

**`helm/ecommerce-platform/charts/order-service/templates/configmap.yaml`**
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "order-service.fullname" . }}-config
data:
  SPRING_PROFILES_ACTIVE: "kubernetes"
  SERVER_PORT: "{{ .Values.containerPort }}"
  LOGGING_LEVEL_COM_ECOMMERCE_ORDER: "{{ .Values.logLevel }}"
```
**`helm/ecommerce-platform/charts/order-service/templates/service.yaml`**
```yaml
apiVersion: v1
kind: Service
spec:
  type: ClusterIP
  selector:
    {{- include "order-service.labels" . | nindent 4 }}
  ports:
    - port: 80
      targetPort: {{ .Values.containerPort }}
```

> **Why `SPRING_PROFILES_ACTIVE` is injected, never baked into the image**
>
> The image built in phase 8 has no idea whether it's about to run in Compose or Kubernetes — it's the exact same jar either way. `SPRING_PROFILES_ACTIVE=kubernetes` arriving as an environment variable from this `ConfigMap`, rather than a build-time argument, is what makes one image portable across every environment in `helm/ecommerce-platform/values-*.yaml` (local, dev, qa, uat, prod) without ever rebuilding it. Build once, configure per-deployment — the same principle phase 4's HS512 secret and phase 6's Kafka bootstrap servers already followed via profile-scoped `application.yml` blocks; this is just Kubernetes' mechanism for supplying it.

4. **Stateful services get a different shape** — Postgres isn't a Deployment. A `StatefulSet` gives each replica a stable identity and its own persistent volume that survives a pod restart:

**`kubernetes/postgres/postgres-orders.yaml` (excerpt)**
```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres-orders
  namespace: data
spec:
  serviceName: postgres-orders
  replicas: 1
  template:
    spec:
      containers:
        - name: postgres
          image: postgres:16-alpine
          readinessProbe:
            exec: { command: ["pg_isready", "-U", "orders_user"] }
            periodSeconds: 10
  volumeClaimTemplates:
    - metadata: { name: data }
      spec:
        accessModes: [ReadWriteOnce]
        resources: { requests: { storage: 50Gi } }
---
apiVersion: v1
kind: Service
metadata:
  name: postgres-orders
  namespace: data
spec:
  clusterIP: None   # headless -- DNS resolves straight to the pod, not a load-balanced VIP
  selector: { app: postgres-orders }
  ports: [{ port: 5432, targetPort: 5432 }]
```

> **`clusterIP: None` and `volumeClaimTemplates`, together**
>
> A normal `ClusterIP` Service load-balances across whichever pods match its selector — fine for stateless order-service replicas that are all interchangeable, wrong for a database where "any Postgres pod" isn't the same as "the Postgres pod with your data." A headless Service (`clusterIP: None`) instead makes DNS resolve directly to the backing pod itself. `volumeClaimTemplates` is the other half: each StatefulSet replica gets its own `PersistentVolumeClaim` that outlives the pod — delete and recreate the pod, the same disk reattaches. In `uat`/`prod` this whole manifest is replaced by a managed Postgres offering instead (RDS, Azure Database for PostgreSQL) — see [architecture.html Part 14](architecture.md#part14) for that tradeoff; local/dev self-hosts because a managed instance per environment isn't worth provisioning for a laptop.

5. **The Helm chart structure** — one umbrella chart, one subchart per service, so `helm upgrade --install` deploys or updates all seven together as a single release:

**`helm/ecommerce-platform/Chart.yaml` (excerpt)**
```yaml
apiVersion: v2
name: ecommerce-platform
dependencies:
  - name: order-service
    version: 1.0.0
    repository: "file://charts/order-service"
  # ...one dependency per service, same shape...
```
**`helm/ecommerce-platform/charts/order-service/templates/_helpers.tpl`**
```yaml
{{- define "order-service.fullname" -}}
order-service
{{- end -}}

{{- define "order-service.labels" -}}
app: {{ include "order-service.fullname" . }}
{{- end -}}
```

> **Why `_helpers.tpl` is this small**
>
> A chart meant for public reuse would compute `fullname` from `.Release.Name` and `.Chart.Name`, so the same chart could be installed multiple times per cluster under different release names without colliding. This platform's charts don't need that — there's exactly one release, named `ecommerce`, once per environment. The helper still exists as the single place every template asks "what's my name," so if that ever needed to change, it changes in one file, not eight.

6. **Autoscaling** — a `HorizontalPodAutoscaler` per service, watching CPU:

**`helm/ecommerce-platform/charts/order-service/templates/hpa.yaml`**
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: {{ include "order-service.fullname" . }} }
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: { type: Utilization, averageUtilization: {{ .Values.autoscaling.targetCPUUtilization }} }
```

7. **Environment-specific values, layered on top of one chart** — the same templates above, different numbers per environment:

**`helm/ecommerce-platform/values-local.yaml` (excerpt)**
```yaml
global:
  namespace: ecommerce
  imageRegistry: ecommerce-local   # built by scripts/build-local-images.sh, no registry push
  imageTag: local

order-service:
  replicaCount: 1
  resources: { requests: { cpu: 250m, memory: 384Mi }, limits: { cpu: 1000m, memory: 768Mi } }
  autoscaling: { minReplicas: 1, maxReplicas: 1 }
```
```bash
helm upgrade --install ecommerce ./helm/ecommerce-platform -f ./helm/ecommerce-platform/values-local.yaml -n ecommerce --create-namespace
```

> **CRITICAL — These specific numbers came from a real load test, not a guess**
>
> The comment actually sitting above these values in this repo tells the real story: an earlier, tighter baseline (100m/256Mi request, 500m/512Mi limit, uniform across all seven services) held up fine at rest but failed under `loadtest/peak-load.js` — `user-service` and `order-service` were both sitting at 60-78% of their 512Mi memory limit at *idle* immediately after just a 5-VU smoke run. The specific culprit for user-service: `BCryptPasswordEncoder` at strength 12 (phase 4) is deliberately CPU-expensive by design, and a single hash was already taking ~700ms with zero concurrent load — so it got the highest CPU limit of any service here (1500m) despite handling less traffic than order-service. That finding also had a caller-side half of the same fix, in `gateway-service`:

**`gateway-service/src/main/resources/application.yml` (excerpt)**
```yaml
resilience4j:
  timelimiter:
    instances:
      # The gateway's CircuitBreaker filter defaults every instance's call timeout to 1s --
      # fine for typical CRUD reads, too tight for user-service: BCrypt strength 12 already
      # takes ~250-700ms per hash by design, and login/signup each do at least one.
      userServiceCB: { timeout-duration: 3s }
```

> **Callee-side and caller-side, both needed**
>
> Raising user-service's own CPU limit (callee-side) without also raising the gateway's circuit-breaker timeout for calls to it (caller-side) would still trip the breaker under load — the callee would eventually have enough CPU to finish a hash, but the caller would already have given up and counted it as a failure. Neither fix alone was sufficient; this is exactly the kind of cross-service tuning [docs/performance-baseline.html](performance-baseline.md) exists to catch, dated and reproducible rather than tribal knowledge.

#### ✓ Checkpoint

```bash
./scripts/build-local-images.sh
helm upgrade --install ecommerce ./helm/ecommerce-platform \
  -f ./helm/ecommerce-platform/values-local.yaml -n ecommerce --create-namespace

kubectl get pods -n ecommerce
# NAME                          READY   STATUS    RESTARTS
# order-service-7d9f...         1/1     Running   0
# (STATUS goes Pending -> ContainerCreating -> Running, then READY flips 0/1 -> 1/1 once
#  the readiness probe -- not just the container starting -- actually passes)

kubectl port-forward -n ecommerce svc/gateway-service 8080:80 &
TOKEN=$(curl -s -X POST localhost:8080/users/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Admin@12345"}' | jq -r .accessToken)
curl -i localhost:8080/orders -H "Authorization: Bearer $TOKEN"
# same request, same response shape, as every checkpoint since phase 4 -- now served by a pod

kubectl get hpa -n ecommerce
# NAME             REFERENCE                   TARGETS   MINPODS   MAXPODS
# order-service    Deployment/order-service     3%/70%    1         1

# Delete a running pod outright and watch the readinessProbe do its job
kubectl delete pod -n ecommerce -l app=order-service
kubectl get pods -n ecommerce -w
# the old pod terminates, a new one appears at 0/1, traffic through the Service keeps
# flowing to whatever's still Running the whole time -- and the new pod only joins
# rotation once /actuator/health/readiness actually returns 200, not on a timer
```

Every phase up to this one asked "does the code work." This one asks "does it survive the platform actually running it" — a pod dying mid-traffic, a slow start not being mistaken for a hang, a resource limit sized from evidence instead of intuition. What's still missing is *seeing* any of this happen without `kubectl` commands run by hand — no dashboards, no traces, no searchable logs yet. Phase 10 is that.

<a id="phase10"></a>
## 10 — Observability

> **Goal:** every service's metrics, traces, and logs are visible in one place — Prometheus/Grafana, Jaeger, and Kibana — without a single `kubectl logs` or `docker logs` command. This closes exactly the gap phase 9's checkpoint named: real failures now happening inside real infrastructure, finally visible instead of inferred.

### Metrics: what a service is doing, in numbers

1. **Every service already exposes this** — `management.endpoints.web.exposure.include: health,info,prometheus` has been in every service's `application.yml` since it was first written, and `micrometer-registry-prometheus` has been a dependency the whole way through. Phase 9's Deployment annotations (`prometheus.io/scrape: "true"`) already told you this endpoint existed; this phase is what actually reads it:

**`monitoring/prometheus-docker.yml`**
```yaml
scrape_configs:
  - job_name: ecommerce-services
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - order-service:8084
          - payment-service:8085
          # ...one entry per service, Docker Compose's DNS resolves each name...
```
**`kubernetes/observability/prometheus.yaml` (excerpt)**
```yaml
scrape_configs:
  - job_name: kubernetes-pods
    kubernetes_sd_configs:
      - role: pod
        namespaces: { names: [ecommerce] }
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: "true"
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        action: replace
        target_label: __metrics_path__
        regex: (.+)
```

> **Same idea as every other docker-vs-kubernetes profile split in this tutorial**
>
> Compose's variant is a static, hand-written list — it works, but someone has to remember to add a line when a new service is added. The Kubernetes variant instead asks the API server directly: "find every pod in the `ecommerce` namespace with `prometheus.io/scrape: true`," discovered continuously, no static list to maintain, and it survives pods being replaced (a new pod IP is picked up automatically — the same self-healing property `scripts/port-forward-persistent.sh` exists to work around for a human's own `kubectl port-forward`).

### Grafana: metrics, made visible without an "add datasource" click

2. **Provisioning, not manual setup** — the datasource and every dashboard load automatically from files, on container start:

**`monitoring/grafana/provisioning/datasources/datasource.yaml`**
```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    url: http://prometheus:9090
    isDefault: true
```
**`monitoring/grafana/provisioning/dashboards/dashboards.yaml`**
```yaml
apiVersion: 1
providers:
  - name: ecommerce-platform
    folder: "E-Commerce Platform"
    type: file
    updateIntervalSeconds: 30
    options:
      path: /var/lib/grafana/dashboards
```

Every service gets its own dashboard, built around the same panel set — the four golden signals (latency, traffic, errors, saturation), plus this platform's own reliability mechanisms made visible:

**`monitoring/grafana/order-service-dashboard.json` (panel titles)**
```text
Request rate
Error rate (%)
p50 / p95 / p99 latency
JVM heap used
Circuit breaker state (inventory-service, payment-service)
HikariCP active connections
```

> **The last two panels aren't generic — they're this platform's own history**
>
> "Circuit breaker state" is phase 5 and phase 7's resilience4j work made visible — you can watch a breaker actually flip `CLOSED`→`OPEN`→`HALF_OPEN` in real time instead of inferring it from a burst of 503s. "HikariCP active connections" is saturation, concretely: a connection pool maxed out under load is a specific, visible number, not a vague sense that things feel slow. `GF_AUTH_ANONYMOUS_ENABLED: "true"` in `docker-compose.yaml`'s `grafana` service (viewer role only) means anyone on the team can open these without a login prompt for local work — write access still requires signing in.

### Traces: what happened, across every service, for one request

3. **Automatic, unlike phase 5's correlation ID** — `spring-boot-starter-opentelemetry` instruments the Feign calls from phase 3 and the HTTP layer without any code in this platform writing a trace header by hand:

**`order-service/src/main/resources/application.yml` (excerpt)**
```yaml
management:
  tracing:
    sampling:
      probability: 1.0   # every request traced, in dev -- see the note below for why not in prod
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: http://otel-collector:4318/v1/traces
```
**`monitoring/otel-collector-config-docker.yaml`**
```yaml
receivers:
  otlp:
    protocols:
      grpc: { endpoint: 0.0.0.0:4317 }
      http: { endpoint: 0.0.0.0:4318 }
processors:
  batch: {}
  memory_limiter: { check_interval: 5s, limit_mib: 512 }
exporters:
  otlp/jaeger: { endpoint: jaeger:4317, tls: { insecure: true } }
  prometheus: { endpoint: 0.0.0.0:8889 }
service:
  pipelines:
    traces: { receivers: [otlp], processors: [memory_limiter, batch], exporters: [otlp/jaeger] }
    metrics: { receivers: [otlp], processors: [memory_limiter, batch], exporters: [prometheus] }
```

> **Two independent paths metrics can take, on purpose**
>
> Prometheus scrapes `/actuator/prometheus` on each service directly (the metrics section above) — that's Micrometer's own registry. The OTel Collector's `prometheus` exporter above is a *second*, separate path: OTel-native metrics (mostly from auto-instrumented libraries) flow through the collector and get their own scrape target (`otel-collector:8889`). Both exist in this stack; they're not redundant, they cover different metric sources.

> **CRITICAL — Trace ID vs. correlation ID — two different tools, not a duplicate**
>
> This might look redundant with the `X-Correlation-Id` phases 3, 5, and 6 built by hand — it isn't. A trace ID is *automatic infrastructure*: Micrometer Tracing generates it, propagates it across an instrumented Feign call without any code here asking it to, and it lives and dies with the OTel pipeline's retention window. Correlation ID is *deliberate application design*: this platform chose to generate it at the gateway, thread it through Feign headers by hand (phase 3's interceptor), and — critically — carry it across the one boundary OTel's HTTP instrumentation can't reach on its own: the async gap through Kafka's outbox pattern (phase 6), where it's stored as a plain database column and re-attached as a Kafka header specifically because nothing does that automatically. In practice both end up in the same structured log line (next section) — search by either, depending on whether you're starting from "a customer quoted this ID back to me" (correlation ID) or "I'm looking at this specific span in Jaeger" (trace ID).

> **`sampling.probability: 1.0` is a dev-only setting**
>
> Tracing every single request is affordable at local/dev traffic volumes and invaluable for a tutorial where you want to see exactly what you just triggered. At production volume it becomes real, ongoing cost (storage, collector throughput) for traces nobody will ever look at — a production deployment would sample a fraction (often 1-10%, plus always-sample-on-error) rather than everything. Not a bug in this reference config, just a choice that's correct for *this* environment specifically, the same way `ddl-auto: validate` everywhere and HS512 in phase 4 were.

### Logs: structured, shipped, and searchable — not scrollback

4. **Every service has emitted structured JSON since it was first written** — `logging.structured.format.console: logstash`, one line, no custom encoder:

```yaml
logging:
  structured:
    format:
      console: logstash
```

> **Trace and span IDs ride along automatically, too**
>
> With `spring-boot-starter-opentelemetry` on the classpath, Boot's structured logging includes `traceId`/`spanId` as top-level JSON fields on every log line, with zero additional configuration — the same mechanism that put `correlationId` into every log line via `MDC.put(...)` in phases 3, 5, and 6 now sits alongside a trace ID Boot populated on its own. One log line, both IDs, either one is a valid way in.

5. **Shipping those JSON lines somewhere searchable** — Filebeat reads container logs, Logstash parses and routes them, Elasticsearch indexes them, Kibana searches them:

**`monitoring/filebeat-docker.yml`**
```yaml
filebeat.autodiscover:
  providers:
    - type: docker
      hints.enabled: true
      templates:
        - condition:
            contains: { docker.container.image: "ecommerce" }
          config:
            - type: container
              paths: ["/var/lib/docker/containers/${data.docker.container.id}/*.log"]
output.logstash:
  hosts: ["logstash:5044"]
```
**`monitoring/logstash/pipeline.conf`**
```text
input { beats { port => 5044 } }
filter {
  json { source => "message" }
  date { match => ["timestamp", "ISO8601"] target => "@timestamp" }
}
output {
  elasticsearch {
    hosts => ["elasticsearch:9200"]
    index => "ecommerce-%{[docker][container][labels][com_docker_compose_service]}-%{+YYYY.MM.dd}"
  }
}
```

> **Autodiscovery again, same shape as metrics scraping**
>
> Filebeat's Docker Compose variant watches the Docker socket directly for any container whose image name contains `ecommerce`; the Kubernetes variant (`monitoring/filebeat.yaml`) instead reads `/var/log/containers/*ecommerce*.log` and enriches each line with pod metadata via `add_kubernetes_metadata`. Same pattern as the Prometheus scrape config split earlier in this phase: Compose gets an explicit rule, Kubernetes asks the platform. One index per service per day (`ecommerce-order-service-2026.07.23`) keeps Kibana searches scoped without cross-service noise by default, while still letting a correlation ID search span every index at once when that's what you actually want.

#### ✓ Checkpoint — one request, traced across every layer

```bash
curl -i localhost:8080/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"customerId":"...","items":[...]}'
# X-Correlation-Id: 7f2c1e9a-...  <- copy this out of the response headers

# 1. Metrics -- Prometheus actually scraping every target
open http://localhost:9090/targets
# every ecommerce-services target State: UP

# 2. Dashboards -- the request you just made shows up as a blip
open http://localhost:3000
# order-service dashboard -> Request rate ticks up, p95 latency panel shows the real number

# 3. Traces -- the exact distributed trace for that one request
open http://localhost:16686
# search Service: order-service -> the newest trace shows spans for order-service,
# the Feign call into payment-service, and the outbox publish -- one waterfall,
# the whole cross-service path this single order-service was built across phases 1-6

# 4. Logs -- search by the correlation ID from the response header, not free-text grep
open http://localhost:5601
# Discover -> correlationId: "7f2c1e9a-..." -> every log line this one request touched,
# across order-service, payment-service, and notification-service, in one result set
```

This is the payoff for everything phases 1 through 9 built carefully rather than quickly: the correlation ID phase 3 started propagating by hand, the outbox row phase 6 wrote specifically to survive the Kafka gap, the circuit breaker state phase 7 fixed the window-sizing bug for — all of it is now something you can actually *see*, for one real request, without SSHing anywhere or guessing. What's left is proving this all holds up under concurrent load, not just one request at a time — phase 11.

<a id="phase11"></a>
## 11 — Load Test & Tune

> **Goal:** prove this holds up under real concurrent load, not just one request at a time. Every checkpoint since phase 4 has proven correctness — the right status code, the right body, for one request. This phase asks a different question entirely: capacity (how much load before it degrades), shock absorption (what happens in the first five seconds of a sudden burst), and stability over time (does it stay healthy after 30 minutes, not just 30 seconds) — using k6 against the real stack, then looking at what a real run actually found.

1. **One shared traffic mix, three different question shapes** — every script below imports the identical flows, so a result is comparable across test shapes instead of secretly exercising different code paths:

**`loadtest/lib/flows.js` (excerpt)**
```javascript
// Traffic mix, same across every script that uses it:
//   browse            (45%) anonymous catalog reads -- no auth overhead
//   order_journey     (30%) full new-customer flow: signup, login, order, track, cancel
//   repeat_login      (15%) an existing customer logging in and checking their orders
//   admin_ops         (10%) admin dashboard-style reads (all orders, notification log)

export function mixedTraffic(data) {
  const r = Math.random();
  if (r < 0.45) browse(data);
  else if (r < 0.75) orderJourney(data);
  else if (r < 0.9) repeatLogin(data);
  else adminOps(data);
  sleep(Math.random() * 0.5 + 0.2); // 200-700ms think time -- not an unrealistic tight loop
}

function flagIfUnexpected(res) {
  // 4xx from the app's own validation/RBAC logic is expected traffic noise under load, not
  // a system failure. 5xx and connection-level failures (status 0) are the real signal.
  const isUnexpected = res.status === 0 || res.status >= 500;
  unexpectedErrors.add(isUnexpected);
}
```

> **Why "unexpected errors" isn't just "any non-2xx"**
>
> A naive load test that counts every 4xx as a failure cries wolf constantly — phase 7's login rate limiter returning `429` under a credential-stuffing-shaped burst is the system working *correctly*, not a bug. `flagIfUnexpected` draws the real line: a `4xx` is the application making a deliberate decision (reject this, rate-limit this, reject this idempotency replay); a `5xx` or a connection-level failure (`status === 0`) means something actually broke. Only the second kind counts toward the threshold that decides whether a run passed.

2. **Three scripts, three different failure modes, same imported flows:**

**`loadtest/peak-load.js` — "where does capacity run out?"**
```javascript
peak: [
  { duration: '30s', target: 20 },  { duration: '45s', target: 20 },
  { duration: '30s', target: 50 },  { duration: '45s', target: 50 },
  { duration: '30s', target: 100 }, { duration: '45s', target: 100 },
  { duration: '30s', target: 200 }, { duration: '45s', target: 200 },
  { duration: '30s', target: 350 }, { duration: '45s', target: 350 },
  { duration: '30s', target: 500 }, { duration: '45s', target: 500 },
  { duration: '30s', target: 0 },
],
thresholds: {
  unexpected_errors: ['rate<0.02'],
  flow_browse_duration: ['p(95)<800'],
  // 4+ inter-service hops (product, inventory x2, payment) -- more room, but flag real
  // degradation once p95 crosses 3s.
  flow_order_journey_duration: ['p(95)<3000'],
},
```
**`loadtest/spike-load.js` — "does it shed load and recover cleanly?"**
```javascript
spike: [
  { duration: '20s', target: 15 },   // baseline
  { duration: '40s', target: 15 },   // hold, let it settle
  { duration: '5s',  target: 400 },  // as close to instant as k6's ramp allows
  { duration: '45s', target: 400 },  // hold at the spike -- shedding should kick in here
  { duration: '10s', target: 15 },   // drop back just as suddenly
  { duration: '60s', target: 15 },   // recovery window -- back to normal, or still degraded?
  { duration: '20s', target: 0 },
],
```
**`loadtest/soak-load.js` — "does it stay healthy over time?"**
```javascript
soak: [
  { duration: '1m', target: 30 },
  { duration: `${__ENV.SOAK_MINUTES || 30}m`, target: 30 },
  { duration: '1m', target: 0 },
],
```

> **Why a burst and a long hold need separate scripts, not one**
>
> A gradual ramp (peak) never produces the failure mode a real flash sale or a retry storm actually causes — an instant jump, not a slope. And a short high-intensity run never surfaces what only shows up after sustained time: a connection pool slowly leaking, the outbox poller or a Kafka consumer rebalance degrading under accumulated state, disk filling from logs. For a spike, success means requests get shed cleanly (some `429`s are fine) and latency returns to baseline in the recovery window — not that nothing gets rejected. For a soak, the real signal is p95 latency and error rate in the *last* 10% of the run compared to the *first* 10% — flat is a pass, drifting upward is the finding, even if every individual request still nominally "succeeded."

3. **Running it, wired into phase 10's Grafana instead of only terminal output:**

```bash
k6 run --env PROFILE=peak \
  --out experimental-prometheus-rw=http://localhost:9090/api/v1/write \
  --tag testid=peak-$(date +%s) \
  loadtest/peak-load.js
```

Watch it live on `monitoring/grafana/loadtest-dashboard.json` instead of waiting for the summary at the end — this is phase 10's whole point paying off: the same Prometheus that scrapes every service's `/actuator/prometheus` also receives k6's own metrics via remote-write, so a load test's request rate sits next to the dashboards it's putting load through.

4. **What a real run against this exact platform actually found** — captured 2026-07-23, one run of all three shapes against both Docker Compose and Kubernetes, back to back, same laptop:

| Run | Peak VUs | Unexpected errors | order_journey p95 |
|---|---:|---:|---:|
| Compose · peak | 500 | 17.08% | 1.65s |
| Compose · spike | 400 | 0.00% | 870ms |
| K8s · peak | 500 | 22.13% | 8.00s |
| K8s · spike | 400 | 51.66% | 6.01s |

> **CRITICAL — Same code, same script, meaningfully worse outcome — and it's explainable, not mysterious**
>
> Every number above traces back to one structural difference: **Kubernetes enforces the Helm chart's per-pod CPU/memory limits from phase 9's `values-local.yaml`; Docker Compose enforces nothing at all.** Under light load that's invisible. Under this load, it produced two real pod restarts, not just slow responses: `gateway-service` was `OOMKilled` once during the peak run (`Exit Code: 137`) against its 768Mi limit, genuinely exceeded at the 500-VU plateau; `user-service` restarted once during the soak run (`Exit Code: 143`, SIGTERM — a liveness-probe timeout under BCrypt-driven CPU contention, not an OOM) at only 30 VUs — the identical load Compose handled at 0.00% unexpected errors. Phase 9's resource tuning (raising limits after a 5-VU smoke test found request failures) was correctly sized for *that* test. It wasn't sized for this one. That's not a contradiction — it's exactly what a peak/spike/soak run at real concurrency exists to find that a smoke test can't.

> **This is the platform working as designed, not failing**
>
> Compose's numbers look better specifically because it has no ceiling to hit — every service can burst to use as much of the host's CPU/memory as it needs. That's not "Compose is more production-ready than Kubernetes"; it's the opposite: unbounded resources hide problems locally that a real cluster's resource limits will always surface eventually. Load-testing against Kubernetes specifically, rather than only the easier Compose numbers, is what turned an invisible problem into two dated, reproducible, actionable findings — [docs/performance-baseline.html](performance-baseline.md) is where they're written up in full, including the honest "known follow-up, not fixed here" note: these limits need another tuning pass informed by *this* data, not the smoke test that originally set them.

#### ✓ Checkpoint

```bash
# Quick sanity pass against whichever stack is up right now -- proves the scripts and the
# stack both actually work, in under a minute, before committing to a full run
k6 run --env PROFILE=smoke loadtest/peak-load.js
k6 run --env PROFILE=smoke loadtest/spike-load.js
k6 run --env PROFILE=smoke loadtest/soak-load.js

# Watch it happen, not just read the summary after
open http://localhost:3000   # loadtest-dashboard.json -- request rate, error rate, p95 live
open http://localhost:16686  # a slow order_journey trace during the run, waterfall and all

# The full, real captured numbers this build produced -- not something to take on faith
open docs/performance-baseline.html
```

Eleven phases ago this was one Spring Boot service with a Postgres connection and nothing else. Every phase since added exactly one real capability, in the order this platform actually needed it — and every phase's checkpoint was a real, runnable command against real, working code, not a diagram. What you have now is the same platform documented in every other file under `docs/`: [architecture.html](architecture.md) is this same system in reference form rather than build order, [local-deployment.html](local-deployment.md) is the operational how-to-run-it guide, [learning-guide.html](learning-guide.md) is a reading path through it for someone who'd rather study the finished code than rebuild it, and [performance-baseline.html](performance-baseline.md) is this phase's findings written up in full. There's no phase 12 — from here, the honest next step for this platform is exactly what phase 11 found: another resource-tuning pass, informed by real data instead of a guess.

---

All 11 phases complete. This tutorial builds the same platform documented in [docs/architecture.html](architecture.md) (the full reference — every part cross-linked from the phase that builds it, above), [docs/local-deployment.html](local-deployment.md) (how to actually run it, every day), [docs/learning-guide.html](learning-guide.md) (a guided reading path for studying the finished code instead of building it from scratch), and [docs/performance-baseline.html](performance-baseline.md) (this phase's load-test findings, in full, dated).
