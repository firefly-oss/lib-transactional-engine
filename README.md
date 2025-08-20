# Firefly OpenCore Banking — Transactional Engine (SAGA Orchestrator)

![Java](https://img.shields.io/badge/Java-21%2B-blue.svg) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg) ![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)

Lightweight, in-memory Saga orchestrator for Spring Boot 3 used within the Firefly OpenCore Banking platform to coordinate transactional workflows across microservices. Each step is an external call (typically HTTP; messaging is also possible using your client of choice). The engine orchestrates ordering, concurrency, retries, timeouts, and compensation (inverse calls) without persisting state.

This library does not replace database transactions; it coordinates cross-service operations using the Saga pattern.

## Table of Contents
- [What is this?](#what-is-this)
- [The Saga pattern in 5 minutes](#the-saga-pattern-in-5-minutes)
- [Key concepts](#key-concepts)
- [Features](#features)
- [Modern API features](#modern-api-features)
  - [Typed StepInputs DSL](#typed-stepinputs-dsl)
  - [SagaResult for comprehensive execution results](#sagaresult-for-comprehensive-execution-results)
  - [Parameter injection](#parameter-injection-in-step-signatures)
  - [Programmatic saga building](#programmatic-saga-building)
  - [Duration-based configuration](#duration-based-configuration)
- [Architecture at a glance](#architecture-at-a-glance)
- [How it works](#how-it-works)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Complete step-by-step tutorial](#complete-step-by-step-tutorial)
- [Migration guide](#migration-guide)
- [Annotations reference](#annotations-reference)
- [Observability](#observability)
- [HTTP integration](#http-integration)
- [Limitations & non-goals](#limitations--non-goals)
- [Compatibility](#compatibility)
- [Common pitfalls (FAQ)](#common-pitfalls-faq)
- [Tests](#tests)
- [Cancellation](#cancellation)
- [License](#license)

## What is this?
The Transactional Engine is a small library that lets you declare Sagas using annotations and execute them in memory:
- You model a business flow as a set of steps with dependencies (a DAG).
- Each step is a method that typically performs an external service call.
- If any step fails, previously completed steps are compensated by calling their inverse methods.

It is designed to be simple, explicit and suitable for orchestrating cross-service operations in OpenCore Banking domains (payments, account operations, order flows, etc.).

## The Saga pattern in 5 minutes
Sagas are a pattern for achieving reliable outcomes across multiple services without using distributed ACID transactions. Each service performs a local transaction and publishes an event or returns a result. If something fails mid‑way, previously completed transactions are compensated by executing explicitly defined inverse operations.

- Orchestration vs. choreography
  - Orchestration: a central orchestrator (this library) directs the steps and compensations.
  - Choreography: services react to events and coordinate themselves. No central brain.
- Local transactions: each step should commit changes atomically in its own service/database.
- Compensation: define a reversible action (refund, release, cancel) to undo business effects.
- When to use: cross‑service workflows such as payments, orders, account provisioning.
- When not to use: single‑service operations that fit well into a single DB transaction or require strict serializability across services.

Simple flow with failure and compensation:

```mermaid
flowchart LR
  A[Start Saga] --> B[Step 1: Reserve Funds]
  B --> C[Step 2: Create Order]
  C --> D[Step 3: Notify]
  C -- fails --> CE[Error]
  CE --> CB[Compensate Step 2: Cancel Order]
  CB --> CA[Compensate Step 1: Release Funds]
  CA --> E[End Saga - failed]
```

## Key concepts
- Saga: a named workflow composed of ordered steps with explicit compensation.
- Step: a unit of work (usually an external call) that may depend on other steps.
- Dependency DAG: dependsOn declares a directed acyclic graph; steps in the same layer run concurrently.
- Compensation: inverse operation invoked for previously completed steps if the saga fails.
- SagaContext: holds correlation id, headers to propagate, per‑step metrics and results.
- Idempotency: optional per‑run short‑circuit via idempotencyKey to skip a step within the same run.
- Resilience controls: retry, fixed backoff, and per‑attempt timeout.
- Observability: lifecycle callbacks via SagaEvents and optional aspect logs.

## Features
- **Modern API (preferred)**
  - Type-safe `StepInputs` DSL with lazy resolvers
  - Typed `SagaResult` API with result and metadata access
  - Multi-parameter injection with `@FromStep`, `@Header`, `@Headers`, `@Input` annotations
  - Programmatic saga building with `SagaBuilder` for dynamic workflows
  - Duration-based configuration for readability (`timeout(Duration)`, `backoff(Duration)`)

- **Annotations & AOP**
  - `@EnableTransactionalEngine` — bootstraps engine, registry, and aspects
  - `@Saga(name)` — marks a class as an orchestrator
  - `@SagaStep(id, compensate, dependsOn?, retry?, timeoutMs?, idempotencyKey?)`

- **Execution model**
  - Builds a DAG from `dependsOn`; executes steps layer-by-layer
  - Steps in the same layer run concurrently
  - Optional per-layer concurrency cap via `@Saga.layerConcurrency`
  - On failure, compensates completed steps in reverse completion order

- **Per-step controls**
  - Retry with fixed/jittered backoff and per-attempt timeout
  - Per-run idempotency using `idempotencyKey` (skips the step within the same saga run)

- **Context and results**
  - `SagaContext` stores correlation id, outbound headers, per-step status/attempts/latency, and step results
  - `SagaResult` provides a typed snapshot of execution results and metadata

- **Observability**
  - Emits lifecycle events via `SagaEvents` (default `SagaLoggerEvents` logs JSON-friendly key=value entries)
  - Minimal `StepLoggingAspect` for raw method invocation latency

- **HTTP integration (WebClient)**
  - `HttpCall` helper to propagate `X-Transactional-Id` and custom headers from `SagaContext`

- **In-memory only**
  - No persistence of saga state. Simple and fast within a single JVM process

## Modern API features

### Typed StepInputs DSL
A type-safe way to provide inputs to saga steps, replacing the older Map-based approach:

```java
// Building concrete inputs
StepInputs inputs = StepInputs.builder()
    .forStepId("reserveFunds", new ReserveCmd("customer-123", 500_00))
    .forStepId("createOrder", new CreateOrderCmd("customer-123", 500_00))
    .build();

// Dynamic inputs with lazy resolvers
StepInputs dynamicInputs = StepInputs.builder()
    .forStepId("issueTicket", ctx -> new IssueTicketReq(
        (FlightRes) ctx.getResult("reserveFlight"),
        (PaymentReceipt) ctx.getResult("capturePayment"),
        ctx.headers().get("X-User-Id")
    ))
    .build();

// Executing with typed inputs
SagaResult result = engine.execute("PaymentSaga", inputs, ctx).block();
```

Resolvers are evaluated right before step execution and cached for compensation if needed.

### SagaResult for comprehensive execution results
The new `execute()` API returns a `SagaResult` that provides typed access to step results and execution metadata:

```java
SagaResult result = engine.execute("TravelSaga", inputs, ctx).block();

// Typed access to step results
if (result.isSuccess()) {
    String bookingId = result.resultOf("initBooking", String.class).orElse(null);
    FlightRes flight = result.resultOf("reserveFlight", FlightRes.class).orElse(null);
    
    // Per-step metadata
    int attempts = result.steps().get("reserveFlight").attempts();
    long latencyMs = result.steps().get("reserveFlight").latencyMs();
}

// Error details for failed executions
if (!result.isSuccess()) {
    String failedStepId = result.firstErrorStepId().orElse(null);
    Throwable error = result.error().orElse(null);
    Set<String> compensatedSteps = result.compensatedSteps();
}

// Context information
String correlationId = result.correlationId();
Map<String, String> headers = result.headers();
Duration executionTime = result.duration();
```

### Parameter injection in step signatures
Annotate step method parameters to have values injected automatically:

```java
@SagaStep(id = "issueTicket", compensate = "cancelTicket", 
          dependsOn = {"reserveFlight", "capturePayment"})
public Mono<Ticket> issueTicket(
    @FromStep("reserveFlight") FlightReservation flight,
    @FromStep("capturePayment") PaymentReceipt payment,
    @Header("X-User-Id") String userId,
    SagaContext ctx
) {
    // Use injected values directly
    return ticketService.issue(flight.id(), payment.id(), userId);
}
```

Supported annotations:
- `@FromStep(stepId)`: injects the result of another step
- `@Header(name)`: injects a specific header
- `@Headers`: injects all headers as Map<String, String>
- `@Input` or `@Input("key")`: injects the step input (or a value from a Map input)

### Programmatic saga building
Define sagas dynamically without annotations using a fluent builder:

```java
SagaDefinition transferSaga = SagaBuilder.saga("transfer")
    .step("debit")
        .timeout(Duration.ofSeconds(2))
        .retry(2)
        .backoff(Duration.ofMillis(100))
        .handler((StepHandler<DebitRequest, Receipt>) (in, ctx) ->
            paymentService.debit(in) // Mono<Receipt>
        )
        .add()
    .step("credit")
        .dependsOn("debit")
        .handler((StepHandler<CreditRequest, Receipt>) (in, ctx) ->
            paymentService.credit(in)
        )
        .add()
    .build();

// Execute with the same API
SagaResult result = sagaEngine.execute(transferSaga, inputs, ctx).block();
```

### Duration-based configuration
More readable way to specify timeouts and backoffs using `java.time.Duration`:

```java
// In annotations (still uses milliseconds for backward compatibility)
@SagaStep(id = "reserveFlight", compensate = "cancelFlight", 
          retry = 2, backoffMs = 300, timeoutMs = 5000)

// In programmatic builder (preferred)
.step("reserveFlight")
    .retry(2)
    .backoff(Duration.ofMillis(300))
    .timeout(Duration.ofSeconds(5))
    .handler(...)
    .add()
```

## Architecture at a glance
This section is a quick, visual guide to how everything fits together. It is designed to be read in under 3 minutes. If you want the in‑depth mechanics, see "How it works" just below.

TL;DR
- You write a Saga class with @Saga and @SagaStep methods.
- At startup, SagaRegistry scans and validates your Sagas.
- At runtime, SagaEngine executes the DAG layer by layer, stores results in SagaContext, emits SagaEvents, and propagates headers to outbound calls via HttpCall.

Runtime sequence for a single step
```mermaid
sequenceDiagram
  participant Caller as YourService
  participant Engine as SagaEngine
  participant Saga as Saga class
  participant Ctx as SagaContext
  participant Events as SagaEvents
  participant IO as HttpCall/WebClient

  Caller->>Engine: execute(sagaName, StepInputs, Ctx)
  Engine->>Saga: invoke @SagaStep method
  Saga->>IO: HTTP call wrapped by HttpCall.propagate(..., Ctx)
  IO-->>Saga: response
  Saga-->>Engine: result
  Engine->>Ctx: store result/status/latency
  Engine->>Events: onStepSuccess/onStepFailed
  Engine-->>Caller: SagaResult
```

Mental map of components
```mermaid
flowchart LR
  subgraph Spring
    CFG[EnableTransactionalEngine]
    CONF[TransactionalEngineConfiguration]
    REG[SagaRegistry]
    ENG[SagaEngine]
    AOP[StepLoggingAspect]
    OBS[SagaEvents]
  end
  subgraph Runtime
    CTX[SagaContext]
    IO[HttpCall]
    INP[StepInputs]
    RES[SagaResult]
  end
  subgraph YourCode
    ORC[Saga class]
  end
  CFG --> CONF
  CONF --> REG
  CONF --> ENG
  CONF --> AOP
  CONF --> OBS
  ORC --- REG
  ENG --- CTX
  ENG --> OBS
  ENG --> ORC
  ENG --> IO
  ENG --- INP
  ENG --- RES
```

Glossary (short and practical)
- SagaRegistry: scans @Saga beans, indexes @SagaStep methods, validates the DAG (no cycles, valid dependsOn, compensations exist).
- SagaEngine: runs the DAG in layers; applies retry, backoff, timeout, idempotency; compensates on failure; emits events.
- SagaContext: per-run state (correlation id, headers, per-step status/attempts/latency/results, idempotency keys).
- StepInputs: your typed inputs per step; supports lazy resolvers that evaluate against SagaContext right before execution.
- SagaResult: immutable snapshot of execution results with typed access to step outputs and metadata.
- SagaEvents: callbacks to integrate with logs/metrics/traces (default is SagaLoggerEvents).
- StepLoggingAspect: optional AOP that logs raw step invocation latency.
- HttpCall: tiny helper to propagate correlation/custom headers to WebClient.

Where to go deeper
- See "How it works" for discovery, DAG layering, retries/backoff/timeout, and compensation semantics.
- See the Annotations reference for exact attributes and supported method signatures.
- See the tutorial for a full end‑to‑end example with visuals and troubleshooting tips.

## How it works
- Discovery
  - The `SagaRegistry` scans the Spring context for `@Saga` beans and their `@SagaStep` methods (and compensation methods by name), validates the DAG (no cycles, dependencies exist), and keeps metadata.
- Execution
  - The `SagaEngine` builds topological layers from the step graph and executes one layer at a time.
  - Steps in the same layer run concurrently. Results are stored in `SagaContext` under the step id.
  - For each step the engine applies (when configured): retry with fixed backoff, per-attempt timeout, and per-run idempotency.
- Failure & compensation
  - If any step fails, the engine aborts remaining layers and compensates already completed steps in reverse completion order.
  - Compensation is best-effort: errors during compensation are logged and swallowed so that remaining compensations can still run.
- Compensation argument resolution
  - If a compensation method expects a business argument, the engine will pass:
    1) the original step input if compatible by type; else
    2) the step result if compatible by type; else
    3) null (especially when only `SagaContext` is expected).


## Installation
Maven

```xml
<dependency>
  <groupId>com.catalis</groupId>
  <artifactId>lib-transactional-engine</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Gradle (Kotlin DSL)

```kotlin
dependencies {
  implementation("com.catalis:lib-transactional-engine:1.0.0-SNAPSHOT")
}
```

## Quick start
Follow this step-by-step guide to model and run your first saga.

1) Enable the engine

```java
@EnableTransactionalEngine
@SpringBootApplication
public class App { }
```

2) Define request models

```java
public record ReserveCmd(String customerId, long amountCents) {}
public record CreateOrderCmd(String customerId, long amountCents) {}
```

3) Create the orchestrator

```java
@Saga(name = "PaymentSaga")
@Service
public class PaymentOrchestrator {
  private final WebClient accounts;
  private final WebClient orders;

  public PaymentOrchestrator(WebClient.Builder builder) {
    this.accounts = builder.baseUrl("http://accounts/api/v1").build();
    this.orders   = builder.baseUrl("http://orders/api/v1").build();
  }

  @SagaStep(
      id = "reserveFunds",
      compensate = "releaseFunds",
      retry = 2,
      backoffMs = 300,
      timeoutMs = 5000
  )
  public Mono<Void> reserveFunds(ReserveCmd cmd, SagaContext ctx) {
    return HttpCall.propagate(
        accounts.post().uri("/balances/reservations").bodyValue(Map.of(
            "customerId", cmd.customerId(),
            "amountCents", cmd.amountCents()
        )), ctx
    ).retrieve().bodyToMono(Void.class);
  }

  public Mono<Void> releaseFunds(ReserveCmd cmd, SagaContext ctx) {
    return HttpCall.propagate(
        accounts.post().uri("/balances/release").bodyValue(Map.of(
            "customerId", cmd.customerId(),
            "amountCents", cmd.amountCents()
        )), ctx
    ).retrieve().bodyToMono(Void.class);
  }

  @SagaStep(
      id = "createOrder",
      dependsOn = {"reserveFunds"},
      compensate = "cancelOrder",
      timeoutMs = 5000
  )
  public Mono<Long> createOrder(CreateOrderCmd cmd, SagaContext ctx) {
    return HttpCall.propagate(
        orders.post().uri("/orders").bodyValue(Map.of(
            "customerId", cmd.customerId(),
            "amountCents", cmd.amountCents()
        )), ctx
    ).retrieve().bodyToMono(Long.class);
  }

  public Mono<Void> cancelOrder(Long orderId, SagaContext ctx) {
    return HttpCall.propagate(
        orders.post().uri("/orders/{id}/cancel", orderId), ctx
    ).retrieve().bodyToMono(Void.class);
  }
}
```

4) Run the saga

```java
@Service
public class PaymentService {
  private final SagaEngine engine;

  public PaymentService(SagaEngine engine) { this.engine = engine; }

  public Mono<Long> process(ReserveCmd reserveCmd, CreateOrderCmd createCmd) {
    SagaContext ctx = new SagaContext(); // auto-generates correlationId (UUID)
    ctx.putHeader("X-User-Id", "123");   // propagate custom headers downstream

    StepInputs inputs = StepInputs.builder()
        .forStepId("reserveFunds", reserveCmd)
        .forStepId("createOrder", createCmd)
        .build();

    return engine
        .execute("PaymentSaga", inputs, ctx)
        .map(r -> r.resultOf("createOrder", Long.class).orElse(null));
  }
}
```

5) What to expect

- Steps within the same layer run concurrently; createOrder waits for reserveFunds thanks to dependsOn.
- If any step fails, the engine compensates already-finished steps in reverse completion order.
- Structured logs are emitted (see Observability); all HTTP calls include `X-Transactional-Id` and your custom headers.

## Complete step-by-step tutorial
If you want a full, realistic, end-to-end tutorial that walks through every feature (DAG modeling, retries/backoff/timeouts, per-run idempotency, compensation semantics, HTTP header propagation, observability hooks, parameter injection, and the new StepInputs DSL with lazy resolvers), read:

- TUTORIAL.md — Travel Booking Saga deep-dive

It includes:
- Visual DAG and compensation plan
- Spring wiring with @EnableTransactionalEngine
- Multi-parameter injection examples using @FromStep, @Header, @Headers, and SagaContext
- Building inputs with StepInputs.builder(), including lazy resolvers that depend on previous results and headers
- Executing sagas by name and programmatically with a fluent builder
- Failure walkthrough with compensation order and context statuses
- Practical guidance on timeouts, retries, and idempotency
- Testing patterns with Reactor StepVerifier


## Migration guide

### Migrating from Map-based run() to typed execute()

Old approach:
```java
Map<String, Object> inputs = Map.of(
    "reserveFunds", new ReserveCmd("customer-123", 500_00),
    "createOrder", new CreateOrderCmd("customer-123", 500_00)
);
Map<String, Object> results = engine.run("PaymentSaga", inputs, ctx).block();
Long orderId = (Long) results.get("createOrder");
```

New approach:
```java
StepInputs inputs = StepInputs.builder()
    .forStepId("reserveFunds", new ReserveCmd("customer-123", 500_00))
    .forStepId("createOrder", new CreateOrderCmd("customer-123", 500_00))
    .build();
SagaResult result = engine.execute("PaymentSaga", inputs, ctx).block();
Long orderId = result.resultOf("createOrder", Long.class).orElse(null);
```

### Using parameter injection instead of manual resolution

Old approach:
```java
@SagaStep(id = "issueTicket", compensate = "cancelTicket")
public Mono<Ticket> issueTicket(SagaContext ctx) {
    FlightRes flight = (FlightRes) ctx.getResult("reserveFlight");
    PaymentReceipt payment = (PaymentReceipt) ctx.getResult("capturePayment");
    String userId = ctx.headers().get("X-User-Id");
    return ticketService.issue(flight.id(), payment.id(), userId);
}
```

New approach:
```java
@SagaStep(id = "issueTicket", compensate = "cancelTicket")
public Mono<Ticket> issueTicket(
    @FromStep("reserveFlight") FlightRes flight,
    @FromStep("capturePayment") PaymentReceipt payment,
    @Header("X-User-Id") String userId,
    SagaContext ctx // still available if needed
) {
    return ticketService.issue(flight.id(), payment.id(), userId);
}
```

### Replacing millisecond values with Duration in programmatic building

Old approach (still works but deprecated):
```java
.step("reserveFlight")
    .retry(2)
    .backoffMs(300)
    .timeoutMs(5000)
```

New approach:
```java
.step("reserveFlight")
    .retry(2)
    .backoff(Duration.ofMillis(300))
    .timeout(Duration.ofSeconds(5))
```

## Annotations reference
| Annotation | Purpose | Key attributes | Notes |
| --- | --- | --- | --- |
| `@EnableTransactionalEngine` | Enables the Transactional Engine Spring configuration: scans for `@Saga` beans, wires `SagaRegistry`, `SagaEngine`, default `SagaEvents`, and the `StepLoggingAspect`. | — | Add on a Spring `@Configuration` (often your `@SpringBootApplication`). |
| `@Saga(name)` | Marks a class as an orchestrator with a human-friendly unique name. Use the same name when invoking `SagaEngine.execute(name, ...)`. | `name` (required); `layerConcurrency` (optional) | Name must be unique across sagas. `layerConcurrency=0` means unbounded concurrency per layer. |
| `@SagaStep` | Declares a saga step and its compensation. | `id` (required); `compensate` (required); `dependsOn[]`; `retry`; `backoffMs`; `timeoutMs`; `idempotencyKey` | Step and compensation method must be on the same class. |

### Additional attributes and notes
- `@Saga(name, layerConcurrency=...)`: optional cap for concurrent steps within the same topological layer. `0` means unbounded concurrency (default). Example:
  ```java
  @Saga(name = "TravelSaga", layerConcurrency = 2)
  @Service
  class TravelSaga { /* ... */ }
  ```
- `@SagaStep` additions:
  - Duration configuration: annotation-based duration fields (timeout/backoff) are deprecated. Prefer SagaBuilder's `timeout(Duration)` / `backoff(Duration)` or rely on defaults (backoff 100ms, timeout disabled). Legacy `timeoutMs`/`backoffMs` are still accepted for backward compatibility.
  - `jitter` and `jitterFactor` (0..1): randomize retry backoff by ±factor. For example, `backoff=1000ms`, `jitter=true`, `jitterFactor=0.5` yields a delay in `[500, 1500]` ms.
  - `cpuBound`: hint that the step is CPU-intensive; the engine will schedule it on Reactor's parallel scheduler.
  - `idempotencyKey`: unchanged; when present, the step can be skipped within the same run if the key was already marked.

Example with new attributes:
```java
@SagaStep(
  id = "reserveFlight",
  compensate = "cancelFlight",
  dependsOn = {"initBooking"},
  retry = 2,
  timeout = "PT2S",
  backoff = "PT300MS",
  jitter = true,
  jitterFactor = 0.4,
  cpuBound = false
)
public Mono<FlightRes> reserveFlight(@Input FlightReq in, SagaContext ctx) { /* ... */ }
```

Supported step method signatures
- (InputType input, SagaContext ctx)
- (InputType input)
- (SagaContext ctx)
- ()
- With parameter annotations: (@FromStep("stepId") ResultType result, @Header("name") String header, ...)

Parameter injection annotations
- `@FromStep("stepId")`: injects the result of another step
- `@Header("name")`: injects a specific header
- `@Headers`: injects all headers as Map<String, String>
- `@Input` or `@Input("key")`: injects the step input (or a value from a Map input)

Return types
- Reactor Mono<T> preferred. Plain T is supported and will be wrapped using `Mono.justOrEmpty`.

Compensation methods
- Declared on the same class, referenced by name via `compensate`.
- Signatures mirror step signatures. When a business argument is expected, the engine will try to pass:
  1) the original step input (if assignable), else
  2) the step result (if assignable), else
  3) null. SagaContext is also injected when declared.

Validation and common errors
- Duplicate step ids in the same saga: IllegalStateException.
- dependsOn references a missing step: IllegalStateException.
- Cycles in the dependency graph: IllegalStateException (validated at startup).
- Missing compensation method by name: IllegalStateException.

Minimal example
```java
@Saga(name = "ExampleSaga")
@Service
class Example {
  @SagaStep(id = "a", compensate = "undoA")
  Mono<String> a(SagaContext ctx) { return Mono.just("ok"); }
  Mono<Void> undoA(String result, SagaContext ctx) { return Mono.empty(); }
}
```

## Programmatic saga execution (fluent, flexible)
In addition to the classic annotation-based discovery and execution, you can now build and run sagas programmatically using a fluent builder and functional step handlers. This is useful when you want to:
- Define a saga within a service/module without annotations
- Compose steps dynamically
- Unit test flows without Spring scanning

Key ideas:
- StepHandler<I,O> is a functional interface: implement execute(input, ctx) returning Mono<O>. Optionally override compensate(arg, ctx) to provide compensation.
- SagaBuilder produces a SagaDefinition that can be executed with the existing SagaEngine.
- The engine prefers handler-based execution when a handler is present; otherwise it falls back to the classic reflection-based method invocation.

Example:

```java
import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.engine.SagaEngine;
import com.catalis.transactionalengine.engine.StepHandler;
import com.catalis.transactionalengine.engine.StepInputs;
import com.catalis.transactionalengine.registry.SagaBuilder;
import com.catalis.transactionalengine.registry.SagaDefinition;
import reactor.core.publisher.Mono;

// Build saga definition fluently
SagaDefinition transferSaga = SagaBuilder.saga("transfer")
    .step("debit")
        .timeoutMs(2_000)
        .retry(2)
        .backoffMs(100)
        .handler((StepHandler<DebitRequest, Receipt>) (in, ctx) ->
            paymentService.debit(in) // Mono<Receipt>
        )
        .add()
    .step("credit")
        .dependsOn("debit")
        .handler((StepHandler<CreditRequest, Receipt>) (in, ctx) ->
            paymentService.credit(in)
        )
        .add()
    .build();

// Execute
SagaContext ctx = new SagaContext();
StepInputs inputs = StepInputs.builder()
    .forStepId("debit", new DebitRequest(/*...*/))
    .forStepId("credit", new CreditRequest(/*...*/))
    .build();
Mono<com.catalis.transactionalengine.core.SagaResult> result = sagaEngine.execute(transferSaga, inputs, ctx);

// Optionally block in non-reactive boundary
com.catalis.transactionalengine.core.SagaResult sagaResult = result.block();
```

Handler-based compensation:

```java
StepHandler<DebitRequest, Receipt> debitHandler = new StepHandler<>() {
    @Override
    public Mono<Receipt> execute(DebitRequest in, SagaContext ctx) {
        return paymentService.debit(in);
    }
    @Override
    public Mono<Void> compensate(Object arg, SagaContext ctx) {
        // arg will be either original input or previous result (pick what you need)
        DebitRequest in = (DebitRequest) arg;
        return paymentService.refund(in).then();
    }
};

SagaDefinition saga = SagaBuilder.saga("with-compensation")
    .step("debit").handler(debitHandler).add()
    .build();
```

Notes:
- All per-step knobs (retry, backoffMs, timeoutMs, idempotencyKey, dependsOn) are supported by the builder and honored by the engine.
- Classic annotation-based usage is unchanged. You can freely mix: a step may have both annotation-discovered method and a handler, and the engine will prefer the handler if present.
- Observability, idempotency and compensation semantics remain the same as described above.

## Step inputs: typed DSL and typed results (SagaResult)
To eliminate Map<String,Object> and stringly-typed step ids from the public API while increasing expressiveness, the engine provides a typed StepInputs DSL and a new execution API that returns a typed SagaResult.

Preferred execution API:
- Mono<SagaResult> execute(String sagaName, StepInputs inputs, SagaContext ctx)
- Mono<SagaResult> execute(SagaDefinition saga, StepInputs inputs, SagaContext ctx)

Backward compatibility: Map-based run(...) overloads remain available but are deprecated and will be removed in a future release. Use execute(...) instead.

Basic usage (programmatic SagaDefinition):

```java
SagaDefinition def = SagaBuilder.saga("S1")
  .step("a").handler((StepHandler<String, String>) (in, ctx) -> Mono.just("A-" + in)).add()
  .step("b").dependsOn("a").handler((StepHandler<Void, String>) (in, ctx) -> Mono.just("B" + ctx.getResult("a"))).add()
  .build();

SagaContext ctx = new SagaContext();

StepInputs inputs = StepInputs.builder()
  .forStepId("a", "in")
  .build();

var sagaResult = sagaEngine.execute(def, inputs, ctx).block();
// sagaResult.resultOf("a", String.class).get().equals("A-in");
// sagaResult.resultOf("b", String.class).get().equals("BA-in");
```

Accessing results and metadata with SagaResult
```java
SagaResult r = sagaEngine.execute(def, inputs, ctx).block();
if (r.isSuccess()) {
  String a = r.resultOf("a", String.class).orElseThrow();
  // Per-step info
  var out = r.steps().get("a");
  int attempts = out.attempts();
  long latency = out.latencyMs();
}
```

Dynamic inputs via resolvers
When an input depends on previously produced results and/or headers, provide a resolver that will be evaluated right before the step runs. The resolved value is cached so compensation can reuse it if needed.

```java
StepInputs inputs = StepInputs.builder()
  .forStepId("issueTicket", c -> new IssueTicketReq(
      (FlightRes) c.getResult("reserveFlight"),
      (PaymentReceipt) c.getResult("capturePayment"),
      c.headers().get("X-User-Id")
  ))
  .build();

sagaEngine.execute("TravelSaga", inputs, ctx);
```

Addressing steps without strings
If you prefer to avoid raw ids, you can pass a Method to the builder, and it will extract the @SagaStep id from it:

```java
class TravelSaga {
  @SagaStep(id = "reserveFlight", compensate = "cancelFlight")
  Mono<FlightReservation> reserveFlight(ReserveFlightReq req, SagaContext ctx) { ... }
}

Method m = TravelSaga.class.getMethod("reserveFlight", ReserveFlightReq.class, SagaContext.class);
StepInputs in = StepInputs.builder().forStep(m, new ReserveFlightReq(...)).build();
```

Note: A convenient API based on direct method references (e.g., TravelSaga::reserveFlight) may be added in a future version. For now, you can use forStepId or provide a Method.

Compensation semantics unchanged
- During compensation, the engine prefers passing the original step input to the compensation method when its parameter type matches; otherwise it tries the step result. The materialized inputs (concrete values plus any resolved and cached values) are used for this decision.

Migration
- Start using StepInputs.builder() and the execute(..., StepInputs, ...) API returning SagaResult.
- The Map-based overloads still work but are deprecated. Prefer the new API for type-safety and better IDE navigation.

## Observability
What is emitted and when
- onStart(sagaName, sagaId): fired once at the beginning of a run.
- onStart(sagaName, sagaId, ctx): same as above, but with access to SagaContext (used e.g. by tracing to inject X-Trace-Id header for propagation).
- onStepStarted(sagaName, sagaId, stepId): when a step transitions to RUNNING.
- onStepSuccess(sagaName, sagaId, stepId, attempts, latencyMs): after a step completes successfully.
- onStepFailed(sagaName, sagaId, stepId, error, attempts, latencyMs): after the final failed attempt of a step.
- onCompensated(sagaName, sagaId, stepId, error): emitted for both successful and failed compensations; error is null on success.
- onStepSkippedIdempotent(sagaName, sagaId, stepId): when a step is skipped due to per-run idempotency (idempotencyKey already marked in this run).
- onCompleted(sagaName, sagaId, success): fired once when the saga finishes (success=false if any step failed).

Default implementation
- `SagaLoggerEvents` emits structured key=value logs via SLF4J. Example lines:
```
saga_event=start saga=PaymentSaga sagaId=2f0b9c3d-... 
saga_event=step_success saga=PaymentSaga sagaId=2f0b9c3d-... stepId=reserveFunds attempts=1 latencyMs=120
saga_event=step_failed saga=PaymentSaga sagaId=2f0b9c3d-... stepId=createOrder attempts=3 latencyMs=5000 error=java.util.concurrent.TimeoutException
saga_event=compensated saga=PaymentSaga sagaId=2f0b9c3d-... stepId=reserveFunds error=
saga_event=completed saga=PaymentSaga sagaId=2f0b9c3d-... success=false
```

Customizing observability
- Provide your own `SagaEvents` bean to export metrics or traces:
```java
@Configuration
class ObservabilityConfig {
  @Bean
  SagaEvents sagaEvents() {
    return new SagaEvents() {
      @Override public void onStart(String saga, String id) { /* meter/tracing start */ }
      @Override public void onStepSuccess(String saga, String id, String step, int attempts, long ms) { /* metrics */ }
      @Override public void onStepFailed(String saga, String id, String step, Throwable err, int attempts, long ms) { /* metrics */ }
      @Override public void onCompensated(String saga, String id, String step, Throwable err) { /* metrics */ }
      @Override public void onCompleted(String saga, String id, boolean success) { /* meter/tracing end */ }
    };
  }
}
```

Auto-configuration and composition
- TransactionalEngineConfiguration provides a composite SagaEvents by default. If you don’t declare your own SagaEvents bean, a CompositeSagaEvents is created that always includes the logger-based SagaLoggerEvents, and conditionally adds SagaMicrometerEvents and SagaTracingEvents when a MeterRegistry or Tracer bean is present.
- Micrometer metrics: if io.micrometer.core.instrument.MeterRegistry is on the classpath and a bean is available, saga.step.* and saga.run.* meters are published automatically.
- Tracing: if io.micrometer.tracing.Tracer is present, SagaTracingEvents creates a saga span and per-step spans. It also injects the current trace id into SagaContext headers as X-Trace-Id via onStart(saga, id, ctx), so HTTP clients can propagate it downstream.
- Override behavior: declare your own @Bean SagaEvents to replace the composite entirely, or declare additional SagaEvents implementations and they will be added into the composite.

Additional AOP logs
- `StepLoggingAspect` wraps raw step method invocation and logs debug-level entries:
```
saga_aspect=step_invocation_success sagaId=2f0b9c3d-... stepId=reserveFunds latencyMs=118
saga_aspect=step_invocation_error   sagaId=2f0b9c3d-... stepId=createOrder  latencyMs=5002 error=...
```
To see these, enable DEBUG for the aspect logger, e.g. in Spring Boot:
```yaml
logging:
  level:
    com.catalis.transactionalengine.aop.StepLoggingAspect: DEBUG
```

## HTTP integration
WebClient with header propagation
```java
@Service
class ClientExample {
  private final WebClient accounts;
  ClientExample(WebClient.Builder builder) {
    this.accounts = builder.baseUrl("http://accounts").build();
  }
  Mono<Void> call(ReserveCmd cmd, SagaContext ctx) {
    return HttpCall.propagate(
        accounts.post().uri("/balances").bodyValue(cmd), ctx
    ).retrieve().bodyToMono(Void.class);
  }
}
```
What is propagated
- `X-Transactional-Id` header using the sagaId: `HttpCall.CORRELATION_HEADER` constant.
- Any custom headers you add via `ctx.putHeader(key, value)`.

Manual propagation with other clients
```java
import org.springframework.http.HttpHeaders;

public class PropagationExample {
  public static HttpHeaders buildHeaders(SagaContext ctx) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpCall.CORRELATION_HEADER, ctx.correlationId());
    ctx.headers().forEach(headers::add);
    return headers;
  }
  // Attach 'headers' to your client of choice (RestTemplate, OkHttp, Feign, etc.)
}
```

Timeouts and retries
- Engine-level: each step can set `timeoutMs`, `retry`, and `backoffMs` (applied per attempt by the engine).
- HTTP client-level: you may also configure client timeouts/retries. Avoid double-retrying the same error path unless intended.

Adding custom headers
```java
public class HeadersExample {
  public static void addHeaders(SagaContext ctx) {
    ctx.putHeader("X-User-Id", "123");
    ctx.putHeader("X-Tenant", "firefly-eu");
  }
}
```

## Parameter injection in step signatures (multi-parameter)
Many steps don’t need external inputs if the engine can inject what they need directly into the method signature.

New annotations:
- `@Input` or `@Input("key")`: injects the step input (or a value from a Map input by key).
- `@FromStep("stepId")`: injects the result of another step.
- `@Header("X-User-Id")`: injects a single outbound header from `SagaContext.headers()`.
- `@Headers`: injects the full headers map (`Map<String, String>`).
- `SagaContext` continues to be injected by type.

Example:

```java
@SagaStep(id = "issueTicket", compensate = "cancelTicket", dependsOn = {"reserveFlight", "capturePayment"})
public Mono<Ticket> issueTicket(
  @FromStep("reserveFlight") FlightReservation flight,
  @FromStep("capturePayment") PaymentReceipt payment,
  @Header("X-User-Id") String userId,
  SagaContext ctx
) {
  // ... call downstream service using flight + payment + userId
}
```

Engine behavior:
- The engine inspects `method.getParameters()` and resolves each argument by annotation or by type (for `SagaContext`).
- Backwards-compatible: legacy signatures still work: `(input, SagaContext)`, `(input)`, `(SagaContext)`, or `()`.
- Validation at startup: if any parameter cannot be resolved, the registry fails fast with a clear error message. It also
  validates that `@FromStep("id")` references an existing step and that `@Headers` is used with a `Map`-typed parameter.

Result: many steps no longer require callers to pass step inputs explicitly.

## Limitations & non-goals
- In-memory only: does not persist saga state or outbox messages.
- No local DB transactions, XA or 2PC.
- Exactly-once is not guaranteed; design your downstreams to be idempotent.
- Compensation is best-effort; compensation errors are logged and swallowed so others can continue.

## Compatibility
- Java 21+
- Spring Framework 6 / Spring Boot 3+
- Reactor (Mono)

## Common pitfalls (FAQ)
- Are compensation methods mandatory? Yes. Provide an opposite operation per step.
- What argument is passed to compensation? The engine tries step input, then step result (by type), else null; `SagaContext` is also passed when declared.
- How is idempotency used? Idempotency is per saga run: if `idempotencyKey` is set and was used earlier in the same run, the step is skipped.
- Can I use my own HTTP client? Yes. `HttpCall` is optional—any client works; just propagate headers yourself.

## Tests

How to run
- All tests: `mvn clean test` (also runs on `mvn clean install`).
- One test class: `mvn -Dtest=com.catalis.transactionalengine.engine.SagaEngineTest test`.
- One test method: `mvn -Dtest=SagaEngineTest#timeoutFailsStep test`.

What the suite covers
- Engine behavior (SagaEngineTest)
  - Successful execution stores results and emits events
  - Retry with backoff increments attempts and eventually succeeds
  - Timeout fails the step, emits failure, completes saga with success=false
  - Compensation is executed for completed steps when a dependent step fails
  - Per-run idempotency skips configured steps while marking them as DONE
- Context (SagaContextTest)
  - Stores statuses, attempts, latency and results per step; supports headers and idempotency keys
- Registry (SagaRegistryTest)
  - Scans @Saga/@SagaStep metadata, validates DAG (deps, cycles, compensation existence)
- HTTP helper (HttpCallTest)
  - Propagates X-Transactional-Id and custom headers from SagaContext
- Spring wiring (TransactionalEngineConfigurationTest)
  - @EnableTransactionalEngine registers SagaEngine, SagaRegistry, default SagaEvents
- End-to-end (FunctionalSagaIT)
  - Bootstraps a Spring context, runs a success saga and a failing saga with compensation and events

Notes
- JUnit 5 is used throughout; Reactor StepVerifier is used where applicable.
- Mockito/ByteBuddy may emit a Java agent warning during tests; it is harmless.
- Java 21+ is required (see badges and pom).

## Cancellation

This library runs steps using Reactor (Mono/Flux). Caller cancellation (disposing the subscription to SagaEngine.run/execute) behaves as follows:

- In-flight steps in the current layer keep running to completion; Reactor does not forcibly interrupt running user code or blocking calls.
- After cancellation, no new steps/layers are started.
- Cancellation alone does not trigger compensation. Compensation is executed only when a step fails and the engine enters the failure path.

Recommendations for cooperative cancellation:
- Ensure your step implementations call cancellable/reactive clients and avoid blocking where possible.
- If you need best-effort abort, use doOnCancel in your Monos to propagate cancellation to downstream clients.
- If business requirements demand compensations on user-initiated abort, design a guard step that can fail fast on a cancellation signal (e.g., via a header) so the engine will enter the compensation path.

API notes:
- SagaBuilder now supports Duration-based configuration methods: backoff(Duration) and timeout(Duration). Millisecond methods (backoffMs/timeoutMs) remain for backward compatibility but are deprecated.
- SagaEvents.onCompensated is emitted for both success and error cases; a null error parameter indicates a successful compensation.

## License
Apache-2.0



