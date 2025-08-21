# Transactional Engine — Saga Orchestrator for Spring Boot 3

![Java](https://img.shields.io/badge/Java-21%2B-blue.svg) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg) ![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)

Lightweight, in-memory Saga orchestrator for Spring Boot 3. Coordinate multi-service workflows with explicit steps, dependencies, retries/timeouts, and compensations — without running a workflow server.

Use this library to orchestrate short‑lived, cross‑service operations (payments, orders, provisioning). It does not replace database transactions and does not persist workflow state.

---

Table of contents
- [Why this library](#why-this-library)
- [When to use / When not to use](#when-to-use--when-not-to-use)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Core concepts](#core-concepts)
- [Compensation policies](#compensation-policies)
- [Graph generation (Sagas DAG via Graphviz)](#graph-generation-sagas-dag-via-graphviz)
- [Best practices](#best-practices)
- [Documentation](#documentation)
- [FAQ / Troubleshooting](#faq--troubleshooting)
- [Compatibility](#compatibility)
- [Versioning](#versioning)
- [Contributing](#contributing)
- [License](#license)

---

## Why this library
- Simple: in‑process orchestration; no external engine to operate.
- Explicit: steps/compensations declared in code; DAG is validated at startup.
- Safe: per‑step retry/backoff/timeout; optional per‑run idempotency.
- Productive: typed StepInputs and SagaResult; parameter injection; HttpCall for header propagation.
- Observable: lifecycle events (SagaEvents) and optional aspect logging.

## When to use / When not to use
When to use
- 2–10 external calls with clear dependencies and compensations.
- Need transparent rollback behavior and operational visibility.

When not to use
- Durable, long‑running workflows that must survive restarts.
- Exactly‑once guarantees across services.
- Choreography-first architectures (events without a central orchestrator).

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
1) Enable in Spring
```java
import com.catalis.transactionalengine.annotations.EnableTransactionalEngine;

@EnableTransactionalEngine
@SpringBootApplication
public class App {}
```

2) Declare a Saga (steps + compensations)
```java
import com.catalis.transactionalengine.annotations.*;
import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.http.HttpCall;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

record ReserveFundsCmd(String customerId, long amountCents) {}
record CreateOrderCmd(String customerId, long amountCents) {}

@Saga(name = "OrderSaga")
@Service
class OrderSaga {
  private final WebClient payments;
  private final WebClient orders;
  OrderSaga(WebClient.Builder b) {
    this.payments = b.baseUrl("http://payments").build();
    this.orders   = b.baseUrl("http://orders").build();
  }

  @SagaStep(id = "reserveFunds", compensate = "releaseFunds", retry = 2)
  Mono<String> reserveFunds(@Input ReserveFundsCmd cmd, SagaContext ctx) {
    return HttpCall.propagate(
      payments.post().uri("/reservations").bodyValue(cmd), ctx
    ).retrieve().bodyToMono(String.class);
  }
  Mono<Void> releaseFunds(@Input ReserveFundsCmd cmd, SagaContext ctx) {
    return HttpCall.propagate(
      payments.post().uri("/reservations/release").bodyValue(cmd), ctx
    ).retrieve().bodyToMono(Void.class);
  }

  @SagaStep(id = "createOrder", dependsOn = {"reserveFunds"}, compensate = "cancelOrder")
  @SetVariable("orderId")
  Mono<Long> createOrder(@Input CreateOrderCmd cmd, @Header("X-User-Id") String user, SagaContext ctx) {
    return HttpCall.propagate(
      orders.post().uri("/orders").bodyValue(cmd), ctx
    ).retrieve().bodyToMono(Long.class);
  }
  Mono<Void> cancelOrder(@Input CreateOrderCmd cmd, SagaContext ctx) {
    return HttpCall.propagate(
      orders.post().uri("/orders/cancel").bodyValue(cmd), ctx
    ).retrieve().bodyToMono(Void.class);
  }
}
```

3) Execute with typed inputs
```java
import com.catalis.transactionalengine.engine.StepInputs;
import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.core.SagaResult;

StepInputs inputs = StepInputs.builder()
  .forStepId("reserveFunds", new ReserveFundsCmd("cust-1", 500_00))
  .forStepId("createOrder", new CreateOrderCmd("cust-1", 500_00))
  .build();

SagaContext ctx = new SagaContext();
ctx.putHeader("X-User-Id", "user-99");
SagaResult result = engine.execute("OrderSaga", inputs, ctx).block();
Long orderId = result.resultOf("createOrder", Long.class).orElse(null);
```

Notes
- Parameter injection: @Input, @FromStep, @Header/@Headers, @Variable/@Variables; SagaContext injected by type.
- HttpCall automatically adds X-Transactional-Id and ctx.headers() to WebClient calls. For other clients, use HttpCall.buildHeaders(ctx).

### Inspecting results and compensations (new)
- `SagaResult` always contains all declared steps in `steps()` preserving declaration order. For each step you can inspect status, attempts, latency, error, and whether it was compensated.
- Compensation outputs and errors are now recorded per step and exposed through `SagaResult.StepOutcome`.
- `SagaReport` provides a friendlier wrapper for common queries like failed/compensated steps and accessing compensation details.

Example
```java
SagaResult sr = engine.execute("OrderSaga", inputs, ctx).block();
// Direct via SagaResult
var outcome = sr.steps().get("reserveFunds");
boolean wasCompensated = outcome.compensated();
Throwable compErr = outcome.compensationError(); // null if success
Object compRes = outcome.compensationResult();   // value if your compensation returned something

// Or use SagaReport for convenient accessors
SagaReport report = SagaReport.from(sr);
List<String> failed = report.failedSteps();
List<String> rolledBack = report.compensatedSteps();
var step = report.steps().get("reserveFunds");
boolean ok = step.compensationError().isEmpty();
```

## Core concepts
- Saga: an orchestrator class annotated with `@Saga(name=...)` that declares the workflow DAG.
- SagaStep: a method annotated with `@SagaStep` that performs a unit of work. It may declare `compensate` and `dependsOn`.
- Compensation: inverse action invoked on failures in reverse order or by groups depending on policy.
- SagaContext: per-run, in-memory context that carries correlation id, headers, variables, per-step attempts/status/latency, and now also compensation results and errors recorded during rollback.
- StepInputs: immutable, typed DSL to supply inputs per step id (supports constants and lazy resolvers).
- SagaResult: immutable read-only view of results and metadata after execution. Each step always appears in the map (even if not run), and includes compensationResult/compensationError fields when applicable.
- SagaReport: convenience wrapper over SagaResult with ergonomic accessors for step outcomes and compensation details.
- HttpCall: helper to propagate correlation and headers into WebClient and to map error bodies to exceptions.

See the [Reference Card](docs/REFERENCE_CARD.md#core-types-runtime) for the complete list and signatures, and the [Tutorial](docs/TUTORIAL.md) for an end-to-end example.


## Compensation policies
- STRICT_SEQUENTIAL (default): compensates one‑by‑one in reverse completion order.
- GROUPED_PARALLEL: compensates by original layers; independent compensations run in parallel per batch.
- RETRY_WITH_BACKOFF: sequential rollback with retry/backoff/timeout semantics applied to each compensation (inherits from the step unless overridden).
- CIRCUIT_BREAKER: sequential rollback that opens a circuit (skips remaining compensations) if a compensation marked as critical fails.
- BEST_EFFORT_PARALLEL: runs all compensations in parallel (best‑effort); errors are recorded via events and do not stop others.

Select via bean config:
```java
import com.catalis.transactionalengine.engine.SagaEngine;
import com.catalis.transactionalengine.observability.SagaEvents;
import com.catalis.transactionalengine.registry.SagaRegistry;
import org.springframework.context.annotation.*;

@Configuration
class EnginePolicyConfig {
  @Bean SagaEngine sagaEngine(SagaRegistry registry, SagaEvents events) {
    return new SagaEngine(registry, events, SagaEngine.CompensationPolicy.GROUPED_PARALLEL);
  }
}
```

### Compensation‑specific configuration (per step)
Compensations can inherit resilience knobs from the step or override them with dedicated attributes on `@SagaStep` (and `@ExternalSagaStep`):
- `compensationRetry` (int): retries for the compensation; `-1` means inherit from `retry`.
- `compensationBackoffMs` (long): backoff between compensation attempts; `-1` means inherit from `backoffMs`/builder backoff.
- `compensationTimeoutMs` (long): per‑attempt timeout for the compensation; `-1` means inherit from `timeoutMs`/builder timeout.
- `compensationCritical` (boolean): mark the compensation as critical (used by `CIRCUIT_BREAKER`).

Example
```java
@SagaStep(
  id = "createOrder",
  compensate = "cancelOrder",
  retry = 2,
  // Prefer Duration-based configuration via SagaBuilder for backoff/timeout
  compensationRetry = 3,
  compensationBackoffMs = 500,
  compensationTimeoutMs = 2_000,
  compensationCritical = true
)
Mono<Long> createOrder(@Input CreateOrderCmd cmd, SagaContext ctx) { /* ... */ }
Mono<Void> cancelOrder(@Input CreateOrderCmd cmd, SagaContext ctx) { /* ... */ }
```

### Passing data to compensations
- The engine chooses what to pass to a compensation based on parameter type:
  - If the first parameter matches the original step input type, the input is passed.
  - Otherwise, if it matches the step result type, the result is passed.
  - Otherwise, the business argument is null. SagaContext can also be declared and is injected by type.
- You can also use annotations in compensations (@Input, @FromStep, @Header/@Headers, @Variable/@Variables) for explicit injection.

Examples
```java
@SagaStep(id = "a", compensate = "undoA")
Mono<String> a(SagaContext ctx) { return Mono.just("A"); }
Mono<Void> undoA(String result, SagaContext ctx) { return Mono.empty(); }

@SagaStep(id = "pay", compensate = "undoPay")
Mono<Receipt> pay(PaymentReq req, SagaContext ctx) { /* call downstream */ }
Mono<Void> undoPay(PaymentReq req, SagaContext ctx) { /* refund using original input */ }

// Context-only compensation
@SagaStep(id = "x", compensate = "undoX")
Mono<Void> x(SagaContext ctx) { return Mono.empty(); }
Mono<Void> undoX(SagaContext ctx) { return Mono.empty(); }
```

Observability
- New `SagaEvents` callbacks provide compensation visibility: `onCompensationStarted`, `onCompensationRetry`, `onCompensationSkipped`, `onCompensationCircuitOpen`, `onCompensationBatchCompleted`.
- Wire a custom `SagaEvents` bean to export metrics/traces; the default logger implementation will log these events.

## Graph generation (Sagas DAG via Graphviz)
Generate a DOT/PNG/SVG diagram of all sagas discovered in your microservice using a simple Maven command. This uses a CLI included in this library that bootstraps a lightweight Spring context, scans your packages for `@Saga`, `@SagaStep`, `@ExternalSagaStep`, and `@CompensationSagaStep`, and outputs a Graphviz graph.

Quick command
```
mvn -q \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.catalis.transactionalengine.tools.SagaGraphGenerator \
  -Dexec.args="--base-packages com.yourcompany.yourservice --output target/sagas.dot --format png" \
  exec:java
```
Notes
- Replace `com.yourcompany.yourservice` with your microservice base package(s). You can pass multiple, comma-separated.
- `--format` can be `dot` (default), `png`, or `svg`. For `png`/`svg`, Graphviz `dot` must be installed and available on PATH.
- The output will be written to `target/sagas.dot` (and `target/sagas.png` if `--format png`).
- If your orchestrators/steps live in test sources, keeping `-Dexec.classpathScope=test` ensures test classes are also scanned.

Optional: add a Maven profile to your microservice
```xml
<profiles>
  <profile>
    <id>generate-saga-graph</id>
    <build>
      <plugins>
        <plugin>
          <groupId>org.codehaus.mojo</groupId>
          <artifactId>exec-maven-plugin</artifactId>
          <version>3.5.0</version>
          <executions>
            <execution>
              <goals><goal>java</goal></goals>
            </execution>
          </executions>
          <configuration>
            <mainClass>com.catalis.transactionalengine.tools.SagaGraphGenerator</mainClass>
            <classpathScope>test</classpathScope>
            <arguments>
              <argument>--base-packages</argument>
              <argument>com.yourcompany.yourservice</argument>
              <argument>--output</argument>
              <argument>${project.build.directory}/sagas.dot</argument>
              <argument>--format</argument>
              <argument>png</argument>
            </arguments>
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```
Usage in your microservice repo:
```
mvn -Pgenerate-saga-graph exec:java
```

Troubleshooting: exec:java ClassNotFoundException
- Symptom: org.codehaus.mojo.exec... ClassNotFoundException: com.catalis.transactionalengine.tools.SagaGraphGenerator
- Causes and fixes:
  - The library is not on your module's classpath. Ensure your microservice declares a dependency on this library (compile scope):
    
    <dependency>
      <groupId>com.catalis</groupId>
      <artifactId>lib-transactional-engine</artifactId>
      <version>${version}</version>
    </dependency>
    
  - You are running exec:java with a narrow classpath. Either:
    - set classpathScope to test when you need test sources scanned (includes compile/runtime deps):
      
      mvn -Dexec.classpathScope=test -Dexec.mainClass=com.catalis.transactionalengine.tools.SagaGraphGenerator exec:java
      
    - or add the library as a plugin dependency to exec-maven-plugin so the class is available even if not a project dependency:
      
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.5.1</version>
        <configuration>
          <mainClass>com.catalis.transactionalengine.tools.SagaGraphGenerator</mainClass>
          <classpathScope>runtime</classpathScope>
        </configuration>
        <dependencies>
          <dependency>
            <groupId>com.catalis</groupId>
            <artifactId>lib-transactional-engine</artifactId>
            <version>${version}</version>
          </dependency>
        </dependencies>
      </plugin>

Visuals and legend
- Steps are boxes; start steps (no dependencies) have a thicker border; terminal steps (no dependents) are light blue.
- CPU-bound steps are filled gold.
- Steps with compensation have dashed borders and an attached hexagon node connected via a dashed edge. When the compensation is declared externally, the hexagon is labeled "compensate (external)".
- Edge arrows represent dependsOn relationships.

 ## Best practices
- Model clear, idempotent compensations per step; compensation is best‑effort and may be retried.
- Set timeouts on remote calls; use bounded retries with modest backoff; consider jitter to avoid thundering herd.
- Propagate correlation/user/tenant headers via HttpCall; avoid secrets in headers/logs.
- Use @SetVariable to capture values (e.g., orderId) for later steps.
- Prefer typed DTOs for inputs/results; avoid raw maps.
- Keep the DAG explicit; validate dependencies and avoid hidden coupling.
- Monitor attempts/latency via SagaResult and wire SagaEvents to metrics/tracing.

## Documentation
- Tutorial — hands‑on walkthrough: [docs/TUTORIAL.md](docs/TUTORIAL.md)
- Reference Card — API and snippets: [docs/REFERENCE_CARD.md](docs/REFERENCE_CARD.md)
- Architecture — internals and diagrams: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Deep dive into the engine: [docs/DEEP_DIVE_INTO_THE_ENGINE.md](docs/DEEP_DIVE_INTO_THE_ENGINE.md)
- Sagas vs TCC — trade‑offs: [docs/SAGA-vs-TCC.md](docs/SAGA-vs-TCC.md)

## FAQ / Troubleshooting
- Step processes a list and partial failure leaves previously inserted items — how to fix? See guidance below.
- How do I propagate correlation and headers? Use `HttpCall.propagate(spec, ctx)` for WebClient, or `HttpCall.buildHeaders(ctx)` for other clients.
- How do I pass values between steps? Inject them with `@FromStep("stepId")` or store them using `@SetVariable("...")` and read with `@Variable`.
- How do retries/backoff/timeout work? You can configure via SagaBuilder with Duration-based methods (`backoff(Duration)`, `timeout(Duration)`) and `retry(int)`, or via the DSL millisecond overloads (`backoffMs(long)`, `timeoutMs(long)`), or via annotations using millisecond fields (`backoffMs`, `timeoutMs`). `retry` remains available in annotations.
- Can I define steps/compensations outside the orchestrator class? Yes — use `@ExternalSagaStep` and `@CompensationSagaStep` to declare them on any Spring bean.
- Does the engine persist state? No — it is in‑memory and intended for short‑lived orchestrations.


### Solve it directly with the DSL (no SagaBuilder) — step by step
This section shows how to fix “Step processes a list; partial failure leaves previously inserted items” using only the DSL (StepInputs + ExpandEach) with an annotation-based saga. No programmatic SagaBuilder is used.

1) Declare your saga and make each step handle ONE item, with a clear compensation for that single item.
```java
// Orchestrator declared with annotations (classic style)
import com.catalis.transactionalengine.annotations.Saga;
import com.catalis.transactionalengine.annotations.SagaStep;
import com.catalis.transactionalengine.annotations.Input;
import com.catalis.transactionalengine.core.SagaContext;
import reactor.core.publisher.Mono;

@Saga(name = "OrderSaga")
public class OrderOrchestrator {

  // Step that handles ONE order. The compensation for this step is deleteOrder(...)
  @SagaStep(id = "insert", compensate = "deleteOrder")
  Mono<Long> insert(@Input Order order, SagaContext ctx) {
    // Do the side effect for a single item (e.g., call a downstream service)
    return gateway.createOrder(order, ctx); // imagine this returns the created id
  }

  // Compensation receives the original input (the Order) so we can undo a single item.
  Mono<Void> deleteOrder(@Input Order order, SagaContext ctx) {
    return gateway.deleteOrder(order, ctx);
  }

  // Any subsequent step depends on "insert". When we expand at runtime,
  // the engine will rewire this dependency to all per-item clones of "insert".
  @SagaStep(id = "notify", dependsOn = {"insert"})
  Mono<Void> notifyDownstream(SagaContext ctx) {
    return notifier.send(ctx);
  }
}
```

2) Run with the DSL and ask the engine to expand per item
```java
import com.catalis.transactionalengine.engine.StepInputs;
import com.catalis.transactionalengine.engine.ExpandEach;
import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.core.SagaResult;

List<Order> items = fetchOrders(); // the list you want to process

StepInputs inputs = StepInputs.builder()
  // Ask the engine to expand "insert" into N clones — one per item.
  // Each clone automatically inherits the compensation method (deleteOrder).
  .forStepId("insert", ExpandEach.of(items, it -> ((Order) it).id())) // or just ExpandEach.of(items)
  .build();

SagaContext ctx = new SagaContext();            // carry headers/variables if you need them
SagaResult result = engine.execute("OrderSaga", inputs, ctx).block(); // execute by saga name (DSL only)
```

3) What happens under the hood (no extra code on your side)
- The engine clones the "insert" step into per-item steps (insert#0..#N by default or insert:SUFFIX when you provide a suffix function).
- If any item fails later, already completed clones are compensated individually using the inherited compensation.
- Any step depending on "insert" is automatically rewired to depend on all clones; it runs only after they finish.
- Results and compensation info are exposed via SagaResult so you can inspect statuses, attempts, latencies, and compensations.

Notes
- This approach keeps your orchestrator simple and typed; inputs stay strongly typed via @Input and StepInputs.
- You can still pass headers, variables, or idempotency keys as usual. The expansion feature only affects the step fan-out and compensation wiring.


## Compatibility
- Java 21+
- Spring Framework 6 / Spring Boot 3+
- Reactor (Mono)

## Versioning
The library follows semantic versioning principles when released. The example coordinates in this README use `1.0.0-SNAPSHOT`; adjust to your published version.

## Contributing
Issues and suggestions are welcome. If you plan a significant change, consider opening an issue first to discuss the approach. Please include use cases and, when possible, tests.

## License
Apache-2.0