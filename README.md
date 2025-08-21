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
- [Programmatic builder](#programmatic-builder)
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

  @SagaStep(id = "reserveFunds", compensate = "releaseFunds", retry = 2, backoffMs = 200, timeoutMs = 3_000)
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

## Programmatic builder
Build sagas without annotations using functional step handlers and Duration-based configuration.
```java
import com.catalis.transactionalengine.registry.SagaBuilder;
import com.catalis.transactionalengine.registry.SagaDefinition;
import com.catalis.transactionalengine.engine.StepHandler;

SagaDefinition transfer = SagaBuilder.saga("transfer")
  .step("debit").retry(2).backoff(java.time.Duration.ofMillis(100)).timeout(java.time.Duration.ofSeconds(2))
    .handler((StepHandler<DebitReq, Receipt>) (in, ctx) -> payment.debit(in))
    .add()
  .step("credit").dependsOn("debit")
    .handler((StepHandler<CreditReq, Receipt>) (in, ctx) -> payment.credit(in))
    .add()
  .build();

engine.execute(transfer, inputs, new SagaContext()).block();
```

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
  retry = 2, backoffMs = 200, timeoutMs = 3_000,
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
- Sagas vs TCC — trade‑offs: [docs/SAGA-vs-TCC.md](docs/SAGA-vs-TCC.md)

## FAQ / Troubleshooting
- Step processes a list and partial failure leaves previously inserted items — how to fix programmatically? See guidance below.
- How do I propagate correlation and headers? Use `HttpCall.propagate(spec, ctx)` for WebClient, or `HttpCall.buildHeaders(ctx)` for other clients.
- How do I pass values between steps? Inject them with `@FromStep("stepId")` or store them using `@SetVariable("...")` and read with `@Variable`.
- How do retries/backoff/timeout work? Configure per step via annotation attributes (`retry`, `backoffMs`, `timeoutMs`) or use the programmatic builder with `Duration`.
- Can I define steps/compensations outside the orchestrator class? Yes — use `@ExternalSagaStep` and `@CompensationSagaStep` to declare them on any Spring bean.
- Does the engine persist state? No — it is in‑memory and intended for short‑lived orchestrations.

### Step processes a list; partial failure leaves previously inserted items — programmatic resolution
Why it happens
- In Sagas, compensations run only for steps that completed successfully. If a single step inserts N items and fails at item k, that step is marked failed and its compensation is not called. Inserts 1..k-1 remain unless you explicitly clean them up.

Recommended programmatic solutions
1) Per-item steps (preferred)
- Model each item as its own step with its own compensation so the engine automatically compensates previously completed items when a later one fails.
- Use SagaBuilder to generate steps dynamically:
```java
var b = SagaBuilder.saga("InsertItems");
for (int i = 0; i < items.size(); i++) {
  var item = items.get(i);
  b.step("insert:" + i)
    .retry(2)
    .backoff(java.time.Duration.ofMillis(200))
    .handler((in, ctx) -> gateway.insert(item, ctx))
    .compensation((arg, ctx) -> gateway.delete(item, ctx))
    .add();
}
SagaDefinition def = b.build();
// Optionally pick a compensation policy with retries/backoff
SagaEngine engine = new SagaEngine(registry, events, SagaEngine.CompensationPolicy.RETRY_WITH_BACKOFF);
```

2) Per-chunk steps (mini-bulk)
- Tradeoff between overhead and atomicity by batching items in small chunks:
```java
List<List<Item>> chunks = partition(items, 20);
var b = SagaBuilder.saga("InsertChunks");
for (int c = 0; c < chunks.size(); c++) {
  List<Item> chunk = chunks.get(c);
  b.step("insert-chunk:" + c)
    .retry(2)
    .backoff(java.time.Duration.ofMillis(200))
    .handler((in, ctx) -> insertChunk(chunk, ctx))
    .compensation((arg, ctx) -> deleteChunk(chunk, ctx))
    .add();
}
SagaDefinition def = b.build();
```

3) Bulk all-or-nothing endpoint (if you control the downstream)
- Expose a transactional bulk insert API that inserts all or none. The step becomes atomic and compensations are simple (or unnecessary if the bulk is truly atomic).

4) Inline cleanup inside a single step (last resort)
- Track successfully inserted IDs; on error, delete them immediately before rethrowing to fail the step:
```java
List<String> ok = new java.util.concurrent.CopyOnWriteArrayList<>();
return reactor.core.publisher.Flux.fromIterable(items)
  .concatMap(it -> gateway.insert(it, ctx).thenReturn(it))
  .doOnNext(it -> ok.add(it.id()))
  .then()
  .onErrorResume(e -> reactor.core.publisher.Flux.fromIterable(ok)
    .concatMap(id -> gateway.deleteById(id, ctx).onErrorResume(err -> reactor.core.publisher.Mono.empty()))
    .then(reactor.core.publisher.Mono.error(e))
  );
```

Notes
- Make create/delete idempotent; propagate `X-Transactional-Id` with `HttpCall`.
- Consider `RETRY_WITH_BACKOFF` policy so compensations inherit retry/backoff unless overridden per step.
- Limit concurrency per layer if the downstream has quotas (`@Saga(layerConcurrency = N)` or builder equivalents).

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
