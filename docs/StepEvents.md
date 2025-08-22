# Step Events — publish one event per step when a saga succeeds

Audience: teams who want to notify downstream systems (Kafka, SQS, etc.) after a saga completed successfully. This feature is transport‑agnostic with a hexagonal port, and ships with a Spring in‑memory default.

---

Table of contents
- [What it does](#what-it-does)
- [How to use](#how-to-use)
- [Publication semantics](#publication-semantics)
- [Default publisher (in‑memory ApplicationEvent)](#default-publisher-in-memory-applicationevent)
- [Plugging custom MQ adapters (Kafka, SQS, …)](#plugging-custom-mq-adapters-kafka-sqs-)
- [External steps and expansion](#external-steps-and-expansion)
- [FAQ](#faq)

## What it does
- When a saga completes successfully (no compensations executed), the engine publishes exactly one event per step that is annotated with `@StepEvent`.
- Each event contains the saga name/id, step id, the step result as payload, a snapshot of headers from SagaContext, timestamp, and extra observability fields (attempts, latencyMs, startedAt, completedAt, resultType).
- Nothing is published if the saga fails (i.e., compensation phase runs) to avoid emitting partially successful workflows.

---

## How to use
Annotate your `@SagaStep` (or `@ExternalSagaStep`) method with `@StepEvent`.

```java
@Saga(name = "OrderSaga")
class OrderOrchestrator {
  @SagaStep(id = "reserveInventory")
  @StepEvent(topic = "inventory.events", type = "INVENTORY_RESERVED", key = "${tenant}")
  public Mono<InventoryReservation> reserveInventory(@Input OrderInput in) {
    // business logic here
    return Mono.just(new InventoryReservation());
  }

  @SagaStep(id = "chargePayment", dependsOn = {"reserveInventory"})
  @StepEvent(topic = "billing.events", type = "PAYMENT_CHARGED")
  public Mono<PaymentReceipt> chargePayment(@FromStep("reserveInventory") InventoryReservation r) {
    // business logic here
    return Mono.just(new PaymentReceipt());
  }
}
```

Minimal parameters
- topic: logical destination (topic/queue/exchange). How it maps depends on the adapter.
- type: optional free‑form type for consumers.
- key: optional partition/routing key.
- enabled: default true. Set false to temporarily disable without removing the annotation.

---

## Publication semantics
- Events are emitted only after the saga finishes and only if it succeeded (no compensations executed).
- One event is emitted for each annotated step, in completion order.
- Payload is the step’s result as returned by the step method (Mono<T> is resolved to T).
- Headers is a snapshot of `SagaContext.headers()` at publication time (use this to pass correlation/user/tenant).

Event envelope type:
```java
public final class StepEventEnvelope {
  public final String sagaName;   // from @Saga(name)
  public final String sagaId;     // SagaContext.correlationId()
  public final String stepId;     // @SagaStep(id)
  public final String topic;      // from @StepEvent
  public final String type;       // from @StepEvent
  public final String key;        // from @StepEvent
  public final Object payload;    // step result
  public final Map<String,String> headers; // context headers snapshot
  public final Instant timestamp; // publication time

  // Extra observability fields
  public final Integer attempts;     // number of attempts for this step
  public final Long latencyMs;       // measured step latency in milliseconds
  public final Instant startedAt;    // when the step started
  public final Instant completedAt;  // when the step completed/published
  public final String resultType;    // class name of the payload
}
```

Format: StepEventEnvelope.toString() outputs a compact JSON-like line (without dumping payload), suitable for logs or lightweight bridges.

---

## Default publisher (in‑memory ApplicationEvent)
Out of the box, if your Spring application does not define a `StepEventPublisher` bean, the engine auto‑configures a default `ApplicationEventStepEventPublisher` which publishes `StepEventEnvelope` through Spring’s ApplicationEvent mechanism.

Consume events in‑process via `@EventListener`:
```java
@Component
class StepEventListener {
  @EventListener
  public void onStepEvent(StepEventEnvelope e) {
    // handle locally (e.g., log, metrics, bridging to another bus)
  }
}
```

---

## Plugging custom MQ adapters (Kafka, SQS, …)
Provide your own Spring bean implementing the hexagonal port `StepEventPublisher`:

```java
@Component
class KafkaStepEventPublisher implements StepEventPublisher {
  private final ReactiveKafkaProducerTemplate<String, byte[]> producer;
  KafkaStepEventPublisher(ReactiveKafkaProducerTemplate<String, byte[]> producer) { this.producer = producer; }

  @Override
  public Mono<Void> publish(StepEventEnvelope e) {
    String topic = e.topic;        // your mapping rules
    String key = (e.key != null ? e.key : e.sagaId);
    byte[] value = serialize(e);   // your serializer
    return producer.send(topic, key, value).then();
  }
}
```

Notes
- If a custom `StepEventPublisher` bean is present, it overrides the default ApplicationEvent publisher.
- The `topic/type/key` fields are advisory — adapters may map/transform them to match their destination model.

---

## External steps and expansion
- `@StepEvent` works on both in‑class `@SagaStep` and `@ExternalSagaStep`.
- If you use ExpandEach inputs (dynamic fan‑out), the cloned steps inherit the event configuration and will each publish on success.

---

## FAQ
- Q: Why not publish on partial success?  
  A: To avoid downstream side effects based on incomplete workflows. You can always observe intermediate progress via `SagaEvents`.

- Q: How do I include extra metadata?  
  A: Put key/values into `SagaContext.headers()` during execution; they are snapshotted into the event envelope.

- Q: Is the event durable?  
  A: The library does not persist; durability depends on your chosen `StepEventPublisher` adapter (e.g., Kafka).
