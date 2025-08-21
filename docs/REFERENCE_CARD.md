# Transactional Engine — Reference Card

A concise, at-a-glance reference for core types, annotations, configuration, and common snippets.

If you are new here, start with:
- [docs/TUTORIAL.md](TUTORIAL.md) — end-to-end walkthrough
- [docs/ARCHITECTURE.md](ARCHITECTURE.md) — deep dive and diagrams
- [docs/SAGA-vs-TCC.md](SAGA-vs-TCC.md) — when to use Sagas vs TCC

---

Quick navigation
- [Core types](#core-types-runtime)
- [Spring setup](#spring-setup)
- [Declare a Saga (annotations)](#declaring-a-saga-annotations)
- [Parameter injection](#parameter-injection-in-step-signatures)
- [Programmatic builder](#programmatic-saga-building-fluent)
- [Execution](#execution)
- [Resilience controls](#retry-backoff-timeout)
- [Idempotency](#idempotency-per-run)
- [Compensation policies](#compensation-policies)
- [HTTP propagation](#http-propagation)
- [Observability events](#observability-events-sagaevents)
- [Common pitfalls](#common-pitfalls)

---

## Core types (runtime)
- `SagaEngine` — orchestrates execution by DAG layers; applies retry/backoff/timeout/idempotency; compensates on failure.
- `SagaRegistry` — scans `@Saga` beans, indexes `@SagaStep` methods, validates DAG (no cycles, dependencies exist).
- `SagaContext` — per-run state: correlationId, outbound headers, variables, per-step status/attempts/latency/results.
- `StepInputs` — builder for typed inputs (values or lazy resolvers evaluated against SagaContext at execution time).
- `SagaResult` — immutable, typed access to per-step results and metadata after execution.
- `SagaEvents` — lifecycle callbacks (default: `SagaLoggerEvents` emits structured logs).
- `StepLoggingAspect` — optional AOP logging of raw method invocation latency.
- `HttpCall` — helper to propagate `X-Transactional-Id` and custom headers to WebClient.

## Spring setup
- `@EnableTransactionalEngine` — registers the engine, registry, and aspects into the Spring context.

Template
```java
@Configuration
@EnableTransactionalEngine
class TxnEngineConfig {}
```

## Declaring a Saga (annotations)
- `@Saga(name)` — marks a class as an orchestrator.

Example `@SagaStep` declaration
```java
@SagaStep(
  id = "uniqueStepId",
  compensate = "compensationMethodName",
  dependsOn = {"prevStepId1", "prevStepId2"}, // optional
  retry = 0,               // optional fixed retry count
  backoffMs = 0,           // optional fixed backoff in ms (or use Duration in builder)
  timeoutMs = 0,           // optional per-attempt timeout in ms
  idempotencyKey = "expr" // optional key to skip step within the same run
)
```

Compensation method parameters — any one of:
- the original step input type
- the step output type
- `SagaContext` (you can also mix with context; if not resolvable, `null` is passed for the business arg)

### External compensation mapping
Use `@CompensationSagaStep(saga = "SagaName", forStepId = "stepId")` on any Spring bean to declare a compensation outside the orchestrator class. When both in-class and external are present, the external mapping takes precedence.

Example
```java
@Saga(name = "OrderSaga")
class Orchestrator {
  @SagaStep(id = "reserveFunds", compensate = "")
  Mono<String> reserveFunds(@Input ReserveCmd cmd) { return Mono.just("ok"); }
}

class CompensationSteps {
  @CompensationSagaStep(saga = "OrderSaga", forStepId = "reserveFunds")
  Mono<Void> releaseFunds(String res, SagaContext ctx) { return Mono.empty(); }
}
```

### External saga steps (new)
Declare saga steps outside the orchestrator class using `@ExternalSagaStep`. This mirrors `@SagaStep` but adds the `saga` attribute to link the step to a target saga by name.

Key points:
- Place `@ExternalSagaStep` on any Spring bean method.
- Provide `saga = "SagaName"` and the `id` for the step. Other attributes mirror `@SagaStep` (dependsOn, retry, timeoutMs/backoffMs, idempotencyKey, etc.).
- You may also specify `compensate = "methodName"` on the same bean. An external `@CompensationSagaStep` on any bean can still override it (external compensation takes precedence over any in-class declaration).
- Duplicated step ids (between in-class and external, or between two external beans) are rejected at startup.

Example
```java
@Saga(name = "InvoiceSaga")
class Orchestrator { /* no step methods here */ }

@Component
class InvoiceSteps {
  @ExternalSagaStep(saga = "InvoiceSaga", id = "reserveCredit", compensate = "releaseCredit")
  Mono<String> reserveCredit(@Input ReserveCreditCmd cmd, SagaContext ctx) { /* ... */ }
  Mono<Void> releaseCredit(String res, SagaContext ctx) { /* ... */ }

  @ExternalSagaStep(saga = "InvoiceSaga", id = "createInvoice", dependsOn = {"reserveCredit"})
  Mono<Long> createInvoice(@FromStep("reserveCredit") String reservationId) { /* ... */ }
}
```

## Parameter injection in step signatures
Use annotations for multi-parameter injection to avoid manual context lookups:
- `@FromStep("stepId") SomeType arg` — injects result from another step.
- `@Input SomeType arg` — injects current step input (typed value from `StepInputs`).
- `@Header("X-Name") String arg` — injects a specific outbound header.
- `@Headers Map<String,String> headers` — injects all outbound headers.
- `@Variable("key") T arg` — injects a variable by key.
- `@Variables Map<String,Object> vars` — injects all variables.
- `SagaContext ctx` — always available if you need full context.

Set or mutate variables using:
- `@SetVariable("k")` — on method return, store value into `SagaContext` variables under key `"k"`.

Copy‑paste template
```java
@Saga(name = "PaymentSaga")
@Service
public class PaymentOrchestrator {
  @SagaStep(id = "reserveFunds", compensate = "releaseFunds")
  public Mono<Void> reserveFunds(@Input ReserveCmd cmd, SagaContext ctx) { /* ... */ }

  public Mono<Void> releaseFunds(ReserveCmd cmd, SagaContext ctx) { /* ... */ }
}
```

## Programmatic saga building (fluent)
Alternative to annotation-driven execution when you need dynamic flows.

```java
SagaResult result = SagaBuilder.named("PaymentSaga")
  .step("reserveFunds", () -> svc.reserve(...))
    .retry(2)
    .backoff(Duration.ofMillis(300))
    .timeout(Duration.ofSeconds(5))
  .step("createOrder", () -> svc.create(...))
    .dependsOn("reserveFunds")
  .executeWith(engine, ctx);
```

## Execution
```java
StepInputs inputs = StepInputs.builder()
  .forStepId("reserveFunds", new ReserveCmd("customer-123", 500_00))
  .forStepId("createOrder", new CreateOrderCmd("customer-123", 500_00))
  .build();

SagaResult result = engine.execute("PaymentSaga", inputs, ctx).block();
Long orderId = result.resultOf("createOrder", Long.class).orElse(null);
```

## Retry, backoff, timeout
- Annotation attributes: `retry`, `backoffMs`, `timeoutMs` (millisecond values).
- Programmatic API preferred for readability:
  - `.retry(int attempts)`
  - `.backoff(Duration)`
  - `.timeout(Duration)`

Tip
- Prefer `Duration` methods in the builder for readability; keep ms attributes in annotations only for simple cases.

### Compensation-specific overrides (per step)
Override resilience for compensations independently of the step using these `@SagaStep`/`@ExternalSagaStep` attributes:
- `compensationRetry` — retries for compensation; `-1` = inherit from `retry`.
- `compensationBackoffMs` — backoff between attempts; `-1` = inherit from `backoffMs`/builder backoff.
- `compensationTimeoutMs` — per-attempt timeout; `-1` = inherit from `timeoutMs`/builder timeout.
- `compensationCritical` — mark as critical; relevant to `CIRCUIT_BREAKER` policy.

Example
```java
@SagaStep(id = "reserveFunds", compensate = "releaseFunds",
  retry = 1, backoffMs = 200,
  compensationRetry = 3, compensationBackoffMs = 500, compensationTimeoutMs = 2_000, compensationCritical = true)
Mono<String> reserveFunds(@Input ReserveCmd cmd, SagaContext ctx) { /* ... */ }
Mono<Void> releaseFunds(@Input ReserveCmd cmd, SagaContext ctx) { /* ... */ }
```

## Idempotency (per-run)
- Provide `idempotencyKey` in `@SagaStep` to skip executing a step within the same saga run when the key was already seen.
- Key is stored in `SagaContext` for the current run only (no persistence across runs).

## Passing data to compensation (rules + examples)
- Preferred argument resolution order by type: input, then result, else null.
- SagaContext is injected by type when declared.
- Use annotations in compensation too: @Input, @FromStep, @Header/@Headers, @Variable/@Variables.

Examples
- Receive step result automatically:
```java
@SagaStep(id = "a", compensate = "undoA") Mono<String> a(SagaContext ctx) { return Mono.just("A"); }
Mono<Void> undoA(String result, SagaContext ctx) { return Mono.empty(); }
```
- Receive original input:
```java
@SagaStep(id = "pay", compensate = "undoPay") Mono<Receipt> pay(PaymentReq req, SagaContext ctx) { /*...*/ }
Mono<Void> undoPay(PaymentReq req, SagaContext ctx) { /* refund */ }
```
- Use injected values explicitly:
```java
Mono<Void> undoB(@FromStep("a") String a, @Header("X-User-Id") String user, SagaContext ctx) { return Mono.empty(); }
```

Troubleshooting
- If the first parameter type doesn’t match input or result, it will be null. Use @Input/@FromStep or change the type.

## Compensation policies
- `STRICT_SEQUENTIAL` (default): exact reverse completion order, one at a time.
- `GROUPED_PARALLEL`: compensates by original DAG layers, running independent compensations in parallel per batch.
- `RETRY_WITH_BACKOFF`: sequential rollback that applies retry/backoff/timeout per compensation (inherits step settings unless overridden).
- `CIRCUIT_BREAKER`: sequential rollback that opens the circuit (skips remaining compensations) when a compensation marked as critical fails.
- `BEST_EFFORT_PARALLEL`: run all compensations in parallel; record errors via events without stopping others.

Select policy by overriding the `SagaEngine` bean:
```java
@Configuration
class EnginePolicyConfig {
  @Bean
  SagaEngine sagaEngine(SagaRegistry registry, SagaEvents events) {
    return new SagaEngine(registry, events, SagaEngine.CompensationPolicy.GROUPED_PARALLEL);
  }
}
```

## HTTP propagation
- All `HttpCall` invocations automatically include `X-Transactional-Id` and any headers stored in `SagaContext.headers()`.
- Add per-call extra headers via `HttpCall.propagate(request, ctx, Map.of("X-Request-Id", ...))`.
- Build headers for non-WebClient clients with `HttpCall.buildHeaders(ctx)` (RestTemplate, Feign, OkHttp, etc.).
- Use `HttpCall.exchangeOrError(request, ctx, SuccessType, ErrorType, mapper)` to parse error JSON on non-2xx and convert it into a domain exception so the step fails.
- For empty-success bodies use `HttpCall.exchangeVoidOrError(request, ctx, ErrorType, mapper)` which returns `Mono<Void>`.

Snippet
```java
record Err(String code, String message) {}
Mono<Res> call(Req req, SagaContext ctx) {
  return HttpCall.exchangeOrError(
      client.post().uri("/do").bodyValue(req), ctx,
      Res.class, Err.class,
      (status, e) -> new DownstreamException(status, e == null ? "UNKNOWN" : e.code(), e == null ? null : e.message())
  );
}
```

## Observability events (SagaEvents)

Core lifecycle
- `onStart(sagaName, sagaId)` and `onStart(sagaName, sagaId, SagaContext)`
- `onStepStarted(sagaName, sagaId, stepId)`
- `onStepSuccess(sagaName, sagaId, stepId, attempts, latencyMs)`
- `onStepFailed(sagaName, sagaId, stepId, error, attempts, latencyMs)`
- `onStepSkippedIdempotent(sagaName, sagaId, stepId)`
- `onCompleted(sagaName, sagaId, success)`

Compensation-specific (new)
- `onCompensationStarted(sagaName, sagaId, stepId)`
- `onCompensationRetry(sagaName, sagaId, stepId, attempt)`
- `onCompensationSkipped(sagaName, sagaId, stepId, reason)`
- `onCompensationCircuitOpen(sagaName, sagaId, stepId)`
- `onCompensationBatchCompleted(sagaName, sagaId, List<String> stepIds, boolean allSuccessful)`
- `onCompensated(sagaName, sagaId, stepId, Throwable error)` — called for both success (error = null) and failure.

Default: `SagaLoggerEvents` logs structured key=value entries. Provide your own SagaEvents bean to export metrics/traces.

## Common pitfalls

| Pitfall | How to avoid |
|---|---|
| Missing compensation | Ensure each `@SagaStep` specifies `compensate` method by name and that it exists on the same class, or declare an external one via `@CompensationSagaStep(saga, forStepId)`. |
| Cycles in `dependsOn` | The registry fails startup if cycles are detected; keep the graph acyclic. |
| Parameter type mismatch | Make sure compensation parameter type matches either the step input or step output (or accept `SagaContext`). |
| Long-running workflows | The engine is in-memory and does not persist state across JVM restarts. |

---

See also
- [TUTORIAL.md](TUTORIAL.md)
- [ARCHITECTURE.md](ARCHITECTURE.md)
- [SAGA-vs-TCC.md](SAGA-vs-TCC.md)
