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
- `@SetVariable(key = "k")` — on method return, store value into `SagaContext` variables under key `"k"`.

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

## Idempotency (per-run)
- Provide `idempotencyKey` in `@SagaStep` to skip executing a step within the same saga run when the key was already seen.
- Key is stored in `SagaContext` for the current run only (no persistence across runs).

## Compensation policies
- `STRICT_SEQUENTIAL` (default): exact reverse completion order, one at a time.
- `GROUPED_PARALLEL`: compensates by original DAG layers, running independent compensations in parallel per batch.

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

## Observability events (SagaEvents)

| Callback | When it fires |
|---|---|
| `onStart(sagaName, correlationId)` | At saga start |
| `onStepStart(stepId)` | Right before a step begins |
| `onStepSuccess(stepId, duration, attempt)` | A step finished successfully |
| `onStepFailed(stepId, error, duration, attempt)` | A step failed after an attempt |
| `onCompensationStart(stepId)` | A compensation begins |
| `onCompensationSuccess(stepId, duration)` | A compensation finished successfully |
| `onCompensationFailed(stepId, error, duration)` | A compensation failed |
| `onComplete(sagaName, status, totalDuration)` | Saga finished (success or failure) |

Default: `SagaLoggerEvents` logs JSON-friendly key=value entries.

## Common pitfalls

| Pitfall | How to avoid |
|---|---|
| Missing compensation | Ensure each `@SagaStep` specifies `compensate` method by name and that it exists on the same class. |
| Cycles in `dependsOn` | The registry fails startup if cycles are detected; keep the graph acyclic. |
| Parameter type mismatch | Make sure compensation parameter type matches either the step input or step output (or accept `SagaContext`). |
| Long-running workflows | The engine is in-memory and does not persist state across JVM restarts. |

---

See also
- [TUTORIAL.md](TUTORIAL.md)
- [ARCHITECTURE.md](ARCHITECTURE.md)
- [SAGA-vs-TCC.md](SAGA-vs-TCC.md)
