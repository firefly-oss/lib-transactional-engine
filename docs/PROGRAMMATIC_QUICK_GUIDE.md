# Programmatic Quick Guide — Step‑by‑Step Tutorial

A practical, opinionated guide to building sagas programmatically (without annotations). Use the programmatic approach when you need dynamic DAGs at runtime, want to assemble workflows outside of Spring components, or prefer functional handlers over annotated methods.

What you will learn
- When to choose the programmatic API over the DSL
- How to build a SagaDefinition step by step
- How to wire dependencies, timeouts, retries, and compensations
- How to execute with typed inputs and inspect results/compensations
- How to model dynamic graphs (fan‑out per item, conditional steps)
- Common patterns and pitfalls

---

1) When to choose the programmatic way
- Dynamic graphs: steps depend on user input, configuration, or data fetched at runtime.
- Outside Spring: build workflows in libraries or utilities without Spring annotations.
- Code‑generated workflows: construct sagas from metadata, OpenAPI specs, or templates.
- Test harnesses: create minimal ad‑hoc sagas for integration tests.
- Mixed styles: combine annotated sagas with programmatic subgraphs.

If your saga is static and lives in a Spring component, the annotated DSL is concise and discoverable. Choose the programmatic API when you need flexibility or do not want to couple to annotations.

---

2) The essentials: SagaDefinition and handlers
- SagaDefinition: an immutable description of the DAG (steps, dependencies, config).
- SagaBuilder: a fluent builder to assemble SagaDefinition.
- StepHandler<I, O>: a functional interface for step implementations. Handlers receive (input, SagaContext) and return Mono<O> (or Mono<Void> for void steps).
- Compensation handler: an optional function mirroring the step style; it receives either the original input or the step result (engine chooses based on the first parameter type) plus SagaContext.

Minimal example
```java
import com.catalis.transactionalengine.registry.SagaBuilder;
import com.catalis.transactionalengine.registry.SagaDefinition;
import com.catalis.transactionalengine.engine.StepHandler;
import com.catalis.transactionalengine.core.SagaContext;

record DebitReq(String accountId, long cents) {}
record CreditReq(String accountId, long cents) {}
record Receipt(String id) {}

SagaDefinition transfer = SagaBuilder.saga("MoneyTransfer")
  .step("debit")
    .retry(2)
    .backoff(java.time.Duration.ofMillis(150))
    .timeout(java.time.Duration.ofSeconds(3))
    .handler((StepHandler<DebitReq, Receipt>) (in, ctx) -> payment.debit(in, ctx))
    .compensation((DebitReq in, SagaContext ctx) -> payment.refund(in, ctx))
    .add()
  .step("credit").dependsOn("debit")
    .handler((StepHandler<CreditReq, Receipt>) (in, ctx) -> payment.credit(in, ctx))
    .compensation((CreditReq in, SagaContext ctx) -> payment.chargeback(in, ctx))
    .add()
  .build();
```
Notes
- Use Duration for retry backoff and timeout. Retries apply per attempt; timeout bounds each attempt.
- If your compensation’s first parameter type matches the step input, the engine passes the original input; if it matches the step result, it passes the result; else it passes null (SagaContext is always injectable by type).

---

3) Wiring dependencies and layers
- dependsOn: declare upstream steps that must complete before the current step runs.
- Start steps: steps with no dependencies.
- Terminal steps: steps with no dependents; often where you read outcomes.
- Layering: steps at the same distance from the start can run concurrently; you can constrain concurrency per layer when needed.

Example with fan‑in/fan‑out
```java
var b = SagaBuilder.saga("ProvisionUser");

b.step("createProfile").handler((in, ctx) -> userSvc.createProfile(ctx)).add();
b.step("allocateQuota").dependsOn("createProfile").handler((in, ctx) -> quotaSvc.allocate(ctx)).add();
b.step("sendWelcome").dependsOn("createProfile").handler((in, ctx) -> mailer.sendWelcome(ctx)).add();

// Notify after both downstream steps complete
b.step("notifyDone").dependsOn("allocateQuota", "sendWelcome").handler((in, ctx) -> notifier.done(ctx)).add();

SagaDefinition def = b.build();
```

---

4) Configure resilience per step (work + compensation)
- retry(n): number of handler retries.
- backoff(Duration): delay between attempts.
- timeout(Duration): per‑attempt timeout.
- compensationRetry/Backoff/Timeout: override defaults for compensation (if needed) when using annotations; in programmatic style, set compensation behavior via engine policy or custom logic per compensation if your API supports it.
- Engine compensation policy: choose how rollback proceeds across steps.

Pick a policy when constructing SagaEngine
```java
import com.catalis.transactionalengine.engine.SagaEngine;

SagaEngine engine = new SagaEngine(registry, events, SagaEngine.CompensationPolicy.RETRY_WITH_BACKOFF);
```
Tip: Prefer bounded retries with modest backoff; always set timeouts for remote calls.

---

5) Executing with StepInputs and reading results
Build typed inputs per step id and execute the definition.
```java
import com.catalis.transactionalengine.engine.StepInputs;
import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.core.SagaResult;

StepInputs inputs = StepInputs.builder()
  .forStepId("debit", new DebitReq("A1", 10_00))
  .forStepId("credit", new CreditReq("A2", 10_00))
  .build();

SagaContext ctx = new SagaContext();
ctx.putHeader("X-User-Id", "user-42");

SagaResult result = engine.execute(transfer, inputs, ctx).block();
Receipt debitReceipt = result.resultOf("debit", Receipt.class).orElse(null);
```
Inspecting outcomes and compensations
```java
var outcome = result.steps().get("debit");
boolean compensated = outcome.compensated();
Throwable compErr = outcome.compensationError();
Object compRes = outcome.compensationResult();
```

---

6) Dynamic graphs at runtime
6.1) Build steps from data
```java
var b = SagaBuilder.saga("ImportFiles");
for (String path : files) {
  b.step("upload:" + path)
    .handler((in, ctx) -> storage.upload(path, ctx))
    .compensation((arg, ctx) -> storage.delete(path, ctx))
    .add();
}
SagaDefinition def = b.build();
```

6.2) Per‑item expansion of a single step
You can also keep one logical step and expand it into N clones at execution time using ExpandEach, preserving compensation per clone.
```java
List<Item> items = fetchItems();
StepInputs inputs = StepInputs.builder()
  .forStepId("insert", ExpandEach.of(items, it -> ((Item) it).id())) // ids: insert:123, insert:456
  .build();
engine.execute(def, inputs).block();
```
Notes
- Clone ids are insert#0..#N by default or insert:SUFFIX if you pass a suffix function.
- Dependents of the original step are rewired to depend on all clones.

6.3) Conditional steps
```java
var b = SagaBuilder.saga("KYC");
if (country.requiresPepCheck()) {
  b.step("pepCheck").handler((in, ctx) -> kyc.checkPep(ctx)).add();
}
SagaDefinition def = b.build();
```

---

7) Common programmatic patterns
- Per‑item steps (preferred for lists): one step per item with explicit compensation; great isolation and automatic rollback per item.
- Per‑chunk steps: batch groups of N items to balance overhead and atomicity.
- Bulk all‑or‑nothing: if downstream supports a transactional bulk endpoint, treat it as one atomic step.
- Last‑resort inline cleanup: inside a single step, keep track of successful items and delete them on error before failing.

Per‑chunk sketch
```java
List<List<Item>> chunks = partition(items, 50);
var b = SagaBuilder.saga("Bulk");
for (int i = 0; i < chunks.size(); i++) {
  List<Item> chunk = chunks.get(i);
  b.step("insert-chunk:" + i)
    .handler((in, ctx) -> insertChunk(chunk, ctx))
    .compensation((arg, ctx) -> deleteChunk(chunk, ctx))
    .add();
}
SagaDefinition def = b.build();
```

---

8) Observability and testing
- SagaEvents: hook into lifecycle events to emit metrics/traces/logs.
- SagaResult and SagaReport: inspect statuses, attempts, latency, and compensation details.
- Testing: build tiny SagaDefinitions in tests; stub handlers and compensations; assert on SagaResult and events.

Example: simple assertion
```java
SagaResult sr = engine.execute(def, inputs, new SagaContext()).block();
assert sr.steps().get("upload:file-1").status().isCompleted();
```

---

9) Migration and anti‑patterns
Migration tips
- Start by mirroring your annotated saga in code using SagaBuilder; keep step ids stable.
- Move shared business logic into services; keep handlers thin and idempotent.
- Introduce compensation after each external side effect before adding concurrency.

Avoid
- One step that loops over many items without per‑item compensation (leaks on partial failure).
- Unbounded retries/timeouts; always cap and add backoff.
- Hidden side effects in compensations; keep them explicit and idempotent.

---

10) Checklist
- Clear step ids and dependencies
- Timeouts and bounded retries on every remote call
- Idempotent handlers and compensations
- Correlation and user headers propagated (use HttpCall helpers)
- Results and compensations inspected in tests

---
Last updated: 2025-08-21
