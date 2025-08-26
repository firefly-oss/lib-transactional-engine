# Firefly Transactional Engine

A high-performance, reactive Saga orchestration engine designed for mission-critical Spring Boot 3 applications. Built for the modern cloud-native era, it provides enterprise-grade distributed transaction management with intelligent compensation strategies, comprehensive observability, and seamless integration with major cloud platforms.

**Key Differentiators:**
- **Zero-Persistence Design**: Pure in-memory execution with automatic context optimization
- **Type-Safe API**: Method reference support with compile-time validation
- **Cloud-Native**: Native integrations for AWS, Azure, and Google Cloud Platform
- **Banking-Grade Reliability**: Built for the **Firefly OpenCore Banking Platform** by Firefly Software Solutions
- **Developer Experience**: Annotation-driven with fluent programmatic alternatives
- **Production Ready**: Comprehensive retry policies, circuit breakers, and compensation strategies

## Table of Contents

- [Quick Start](#quick-start)
- [Core Features](#core-features)
- [Saga Definition](#saga-definition)
  - [Annotation-Based Sagas](#annotation-based-sagas)
  - [External Steps](#external-steps)
  - [Programmatic Sagas](#programmatic-sagas)
- [Step Configuration](#step-configuration)
- [Parameter Injection](#parameter-injection)
- [Compensation](#compensation)
- [Execution](#execution)
- [Event Publishing](#event-publishing)
- [HTTP Integration](#http-integration)
- [Observability](#observability)
- [Advanced Features](#advanced-features)
- [License](#license)

## Quick Start

### Add Dependency

```xml
<dependency>
    <groupId>com.catalis</groupId>
    <artifactId>lib-transactional-engine</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Enable Engine

```java
@SpringBootApplication
@EnableTransactionalEngine
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### Define Saga

```java
@Component
@Saga(name = "order-processing")
public class OrderProcessingSaga {
    
    @SagaStep(id = "validate-payment")
    public Mono<PaymentResult> validatePayment(@Input("orderId") String orderId) {
        return paymentService.validate(orderId);
    }
    
    @SagaStep(id = "reserve-inventory", 
              dependsOn = "validate-payment",
              compensate = "releaseInventory")
    public Mono<ReservationResult> reserveInventory(
            @FromStep("validate-payment") PaymentResult payment) {
        return inventoryService.reserve(payment.getItems());
    }
    
    public Mono<Void> releaseInventory(
            @FromStep("reserve-inventory") ReservationResult reservation) {
        return inventoryService.release(reservation.getReservationId());
    }
}
```

### Execute Saga

```java
@RestController
public class OrderController {
    
    private final SagaEngine sagaEngine;
    
    @PostMapping("/orders")
    public Mono<SagaResult> createOrder(@RequestBody CreateOrderRequest request) {
        return sagaEngine.execute("order-processing", 
            StepInputs.of("orderId", request.getOrderId()));
    }
}
```

## Core Features

- **Saga Pattern**: Distributed transaction orchestration with automatic compensation
- **Reactive**: Built on Project Reactor for non-blocking operations
- **Annotation-Driven**: Simple DSL with programmatic alternatives
- **Event Publishing**: Built-in event framework with observability
- **Compensation Strategies**: Multiple policies for error recovery
- **Cloud Integration**: Native AWS, Azure, and Google Cloud support
- **Method References**: Type-safe execution using Class::method syntax
- **Automatic Optimization**: Context optimization for sequential vs concurrent execution
- **Step Expansion**: Dynamic step generation with ExpandEach

## Saga Definition

### Annotation-Based Sagas

Define sagas using the `@Saga` annotation on Spring components:

```java
@Component
@Saga(name = "payment-saga", layerConcurrency = 5)
public class PaymentSaga {
    
    @SagaStep(id = "validate", retry = 3, backoffMs = 1000)
    public Mono<ValidationResult> validate(@Input PaymentRequest request) {
        return validationService.validate(request);
    }
    
    @SagaStep(id = "process", dependsOn = "validate", timeoutMs = 30000)
    public Mono<PaymentResult> process(
            @FromStep("validate") ValidationResult validation,
            @Header("X-User-Id") String userId) {
        return paymentService.process(validation, userId);
    }
}
```

### External Steps

Define steps outside the main saga class:

```java
@Component
public class ExternalSteps {
    
    @ExternalSagaStep(saga = "payment-saga", id = "notify", 
                      dependsOn = "process", compensate = "cancelNotification")
    public Mono<Void> sendNotification(@FromStep("process") PaymentResult result) {
        return notificationService.send(result);
    }
    
    public Mono<Void> cancelNotification(@FromStep("notify") Void input) {
        return notificationService.cancel();
    }
}
```

### Programmatic Sagas

Build sagas programmatically using the fluent API:

```java
@Configuration
public class SagaConfig {
    
    @Bean
    public SagaDefinition paymentSaga() {
        return SagaBuilder.named("programmatic-payment")
            .step("validate")
                .handler((PaymentRequest req, SagaContext ctx) -> 
                    validationService.validate(req))
                .retry(3)
                .backoffMs(1000)
                .compensation((result, ctx) -> 
                    validationService.rollback(result))
                .add()
            .step("process")
                .dependsOn("validate")
                .handler((ValidationResult validation, SagaContext ctx) -> 
                    paymentService.process(validation))
                .timeoutMs(30000)
                .add()
            .build();
    }
}
```

## Step Configuration

Configure step behavior with comprehensive options:

```java
@SagaStep(
    id = "complex-step",
    dependsOn = {"step1", "step2"},
    retry = 5,
    backoffMs = 2000,
    timeoutMs = 60000,
    jitter = true,
    jitterFactor = 0.3,
    cpuBound = true,
    idempotencyKey = "unique-key",
    compensationRetry = 3,
    compensationTimeoutMs = 10000,
    compensationCritical = true
)
public Mono<Result> complexStep(@Input Request request) {
    return service.process(request);
}
```

## Parameter Injection

Inject various parameters into step methods:

```java
@SagaStep(id = "multi-param-step")
public Mono<Result> processStep(
        @Input("orderId") String orderId,                    // Specific input field
        @Input OrderRequest fullRequest,                     // Full input object
        @FromStep("previous-step") PreviousResult previous,  // Result from another step
        @Header("X-Correlation-Id") String correlationId,   // Single header
        @Headers Map<String, String> allHeaders,            // All headers
        @Variable("customVar") String variable,             // Custom variable
        SagaContext context) {                              // Full context
    return service.process(orderId, previous, correlationId);
}
```

## Compensation

### In-Class Compensation

```java
@SagaStep(id = "reserve-funds", compensate = "releaseFunds")
public Mono<ReservationId> reserveFunds(@Input ReserveRequest request) {
    return fundService.reserve(request);
}

public Mono<Void> releaseFunds(@FromStep("reserve-funds") ReservationId id) {
    return fundService.release(id);
}
```

### External Compensation

```java
@Component
public class CompensationHandlers {
    
    @CompensationSagaStep(saga = "payment-saga", forStepId = "reserve-funds")
    public Mono<Void> releaseReservedFunds(
            @FromStep("reserve-funds") ReservationId id,
            SagaContext context) {
        return fundService.release(id);
    }
}
```

### Compensation Policies

```java
@Bean
public SagaEngine sagaEngine(SagaRegistry registry, SagaEvents events) {
    return new SagaEngine(registry, events, 
        CompensationPolicy.BEST_EFFORT_PARALLEL);
}
```

Available policies:
- `STRICT_SEQUENTIAL`: Compensate in reverse order
- `GROUPED_PARALLEL`: Compensate by dependency layers
- `RETRY_WITH_BACKOFF`: Retry failed compensations
- `CIRCUIT_BREAKER`: Skip compensation after threshold
- `BEST_EFFORT_PARALLEL`: Parallel compensation, continue on errors

## Execution

### Basic Execution

```java
// By saga name
Mono<SagaResult> result = sagaEngine.execute("payment-saga", 
    StepInputs.of("orderId", "12345"));

// By saga class
Mono<SagaResult> result = sagaEngine.execute(PaymentSaga.class, 
    StepInputs.of("orderId", "12345"));

// By method reference
Mono<SagaResult> result = sagaEngine.execute(PaymentSaga::validate, 
    StepInputs.of("orderId", "12345"));
```

### Advanced Input Building

```java
StepInputs inputs = StepInputs.builder()
    .forStepId("validate", paymentRequest)
    .forStep(PaymentSaga::process, processData)
    .forStepId("notify", ctx -> ctx.getResult("process"))  // Lazy resolver
    .build();

Mono<SagaResult> result = sagaEngine.execute("payment-saga", inputs);
```

### Custom Context

```java
SagaContext context = new SagaContext("correlation-123");
context.setHeader("X-User-Id", "user-456");
context.setVariable("region", "us-east-1");

Mono<SagaResult> result = sagaEngine.execute("payment-saga", inputs, context);
```

## Event Publishing

Implement custom event publishing for integration with messaging systems:

```java
@Component
public class KafkaStepEventPublisher implements StepEventPublisher {
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    @Override
    public Mono<Void> publish(StepEventEnvelope event) {
        return Mono.fromFuture(
            kafkaTemplate.send("saga-events", event.getStepId(), event)
        ).then();
    }
}
```

Configure step events on individual steps:

```java
@SagaStep(id = "important-step")
@StepEvent(topic = "payment-events", eventType = "PAYMENT_PROCESSED")
public Mono<PaymentResult> processPayment(@Input PaymentRequest request) {
    return paymentService.process(request);
}
```

## HTTP Integration

Built-in HTTP utilities for external service calls:

```java
@SagaStep(id = "call-external-service")
public Mono<ExternalResult> callExternalService(
        @Input ExternalRequest request, 
        SagaContext context) {
    
    return HttpCall.exchangeOrError(
        webClient.post().uri("/external/api").bodyValue(request),
        context,
        ExternalResult.class,
        ErrorResponse.class,
        (status, error) -> new ExternalServiceException(status, error.getMessage())
    );
}
```

Header propagation:

```java
return HttpCall.propagate(
    webClient.get().uri("/service"), 
    context,
    Map.of("X-Custom-Header", "value")
).retrieve().bodyToMono(Result.class);
```

## Observability

### Custom Event Handlers

```java
@Component
public class CustomSagaEvents implements SagaEvents {
    
    @Override
    public void onStart(String sagaName, String sagaId) {
        log.info("Saga {} started with ID {}", sagaName, sagaId);
    }
    
    @Override
    public void onStepSuccess(String sagaName, String sagaId, String stepId, 
                             int attempts, long latencyMs) {
        meterRegistry.counter("saga.step.success", 
            "saga", sagaName, "step", stepId).increment();
    }
    
    @Override
    public void onStepFailed(String sagaName, String sagaId, String stepId, 
                            Throwable error, int attempts, long latencyMs) {
        log.error("Step {} failed in saga {}", stepId, sagaName, error);
    }
}
```

### Result Analysis

```java
SagaResult result = sagaEngine.execute("payment-saga", inputs).block();

if (result.isSuccess()) {
    PaymentResult payment = result.resultOf("process", PaymentResult.class)
        .orElseThrow();
    log.info("Payment processed: {}", payment.getId());
} else {
    List<String> failedSteps = result.failedSteps();
    List<String> compensatedSteps = result.compensatedSteps();
    log.error("Saga failed. Failed steps: {}, Compensated: {}", 
        failedSteps, compensatedSteps);
}
```

## Advanced Features

### Step Expansion

Dynamically generate steps for collections:

```java
List<OrderItem> items = Arrays.asList(
    new OrderItem("item1"), 
    new OrderItem("item2")
);

StepInputs inputs = StepInputs.builder()
    .forStepId("process-items", ExpandEach.of(items)
        .withIdSuffix(item -> ":" + item.getId()))
    .build();
```

### Method References

Type-safe saga execution:

```java
// Execute by method reference
Mono<SagaResult> result = sagaEngine.execute(
    PaymentSaga::validatePayment, 
    StepInputs.of("orderId", "12345")
);

// Build inputs with method references
StepInputs inputs = StepInputs.builder()
    .forStep(PaymentSaga::validatePayment, paymentRequest)
    .forStep(PaymentSaga::processPayment, processData)
    .build();
```

### Variables and Context

```java
@SagaStep(id = "set-context")
@SetVariable(name = "processedAt", value = "#{T(java.time.Instant).now()}")
@SetVariable(name = "userId", fromHeader = "X-User-Id")
public Mono<Result> processWithContext(@Variable("region") String region) {
    return service.process(region);
}
```

## License

Licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Links

- **GitHub**: [firefly-oss/lib-transactional-engine](https://github.com/firefly-oss/lib-transactional-engine)
- **Issues**: [GitHub Issues](https://github.com/firefly-oss/lib-transactional-engine/issues)

---

**Firefly Software Solutions Inc** - Building the future of banking technology