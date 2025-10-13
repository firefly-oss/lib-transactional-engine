# SAGA Pattern Guide

This guide provides comprehensive documentation for using the SAGA pattern in the Firefly Transactional Engine.

## Overview

The SAGA pattern implements distributed transactions as a sequence of local transactions with compensating actions. Each step in a saga can be compensated if a later step fails, ensuring eventual consistency across distributed services.

## Key Concepts

### SAGA Characteristics

- **Eventual Consistency**: Data becomes consistent over time
- **Forward Recovery**: Execute compensations in reverse order
- **Isolation**: Limited isolation between concurrent sagas
- **Durability**: State can be persisted for recovery

### When to Use SAGA

✅ **Good for**:
- Long-running business processes
- Microservices orchestration
- When eventual consistency is acceptable
- Complex workflows with multiple services

❌ **Avoid when**:
- Strong consistency is required
- Immediate rollback is needed
- Simple two-phase operations

## Basic SAGA Definition

### Annotation-Based SAGA

```java
@Component
@Saga(name = "order-processing")
public class OrderProcessingSaga {
    
    @SagaStep(id = "validate-order")
    public Mono<OrderValidation> validateOrder(@Input("orderId") String orderId) {
        return orderService.validate(orderId);
    }
    
    @SagaStep(id = "reserve-inventory", 
              dependsOn = "validate-order",
              compensate = "releaseInventory")
    public Mono<InventoryReservation> reserveInventory(
            @FromStep("validate-order") OrderValidation validation) {
        return inventoryService.reserve(validation.getItems());
    }
    
    @SagaStep(id = "process-payment", 
              dependsOn = "reserve-inventory",
              compensate = "refundPayment")
    public Mono<PaymentResult> processPayment(
            @FromStep("validate-order") OrderValidation validation,
            @FromStep("reserve-inventory") InventoryReservation reservation) {
        return paymentService.charge(validation.getAmount());
    }
    
    // Compensation methods
    public Mono<Void> releaseInventory(
            @FromStep("reserve-inventory") InventoryReservation reservation) {
        return inventoryService.release(reservation.getId());
    }
    
    public Mono<Void> refundPayment(
            @FromStep("process-payment") PaymentResult payment) {
        return paymentService.refund(payment.getTransactionId());
    }
}
```

### Programmatic SAGA

```java
@Configuration
public class SagaConfig {
    
    @Bean
    public SagaDefinition orderProcessingSaga() {
        return SagaBuilder.named("order-processing-programmatic")
            .step("validate")
                .handler((OrderRequest req, SagaContext ctx) -> 
                    orderService.validate(req))
                .add()
            .step("reserve")
                .dependsOn("validate")
                .handler((OrderValidation validation, SagaContext ctx) -> 
                    inventoryService.reserve(validation.getItems()))
                .compensation((InventoryReservation reservation, SagaContext ctx) -> 
                    inventoryService.release(reservation.getId()))
                .add()
            .step("payment")
                .dependsOn("reserve")
                .handler((OrderValidation validation, SagaContext ctx) -> 
                    paymentService.charge(validation.getAmount()))
                .compensation((PaymentResult payment, SagaContext ctx) -> 
                    paymentService.refund(payment.getTransactionId()))
                .add()
            .build();
    }
}
```

## Step Configuration

### Basic Step Options

```java
@SagaStep(
    id = "complex-step",
    dependsOn = {"step1", "step2"},           // Dependencies
    retry = 5,                                // Retry attempts
    backoffMs = 2000,                         // Backoff between retries
    timeoutMs = 60000,                        // Step timeout
    jitter = true,                            // Add jitter to backoff
    jitterFactor = 0.3,                       // Jitter factor (0.0-1.0)
    cpuBound = true,                          // CPU-bound operation hint
    idempotencyKey = "unique-key",            // Idempotency key
    compensate = "compensateComplexStep"      // Compensation method
)
public Mono<Result> complexStep(@Input Request request) {
    return service.process(request);
}
```

### Compensation Configuration

```java
@SagaStep(
    id = "critical-step",
    compensate = "compensateCriticalStep",
    compensationRetry = 3,                    // Compensation retry attempts
    compensationTimeoutMs = 10000,            // Compensation timeout
    compensationCritical = true               // Mark compensation as critical
)
public Mono<Result> criticalStep(@Input Request request) {
    return service.process(request);
}

public Mono<Void> compensateCriticalStep(
        @FromStep("critical-step") Result result) {
    return service.compensate(result);
}
```

## Parameter Injection

### Input Parameters

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

### Context Variables

```java
@SagaStep(id = "set-variables")
@SetVariable(name = "processedAt", value = "#{T(java.time.Instant).now()}")
@SetVariable(name = "userId", fromHeader = "X-User-Id")
public Mono<Result> processWithVariables(@Variable("region") String region) {
    return service.process(region);
}
```

## External Steps

Define steps outside the main saga class:

```java
@Component
public class ExternalSteps {
    
    @ExternalSagaStep(saga = "order-processing", 
                      id = "send-notification", 
                      dependsOn = "process-payment",
                      compensate = "cancelNotification")
    public Mono<NotificationResult> sendNotification(
            @FromStep("process-payment") PaymentResult payment) {
        return notificationService.send(payment);
    }
    
    public Mono<Void> cancelNotification(
            @FromStep("send-notification") NotificationResult notification) {
        return notificationService.cancel(notification.getId());
    }
}
```

## Compensation Strategies

### External Compensation

```java
@Component
public class CompensationHandlers {
    
    @CompensationSagaStep(saga = "order-processing", forStepId = "process-payment")
    public Mono<Void> refundPaymentExternal(
            @FromStep("process-payment") PaymentResult payment,
            SagaContext context) {
        return paymentService.refund(payment.getTransactionId());
    }
}
```

### Compensation Policies

Configure compensation execution strategy:

```java
@Bean
public SagaEngine sagaEngine(SagaRegistry registry, SagaEvents events) {
    return new SagaEngine(registry, events, 
        CompensationPolicy.GROUPED_PARALLEL);
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
@Service
public class OrderService {
    
    @Autowired
    private SagaEngine sagaEngine;
    
    public Mono<OrderResult> processOrder(OrderRequest request) {
        // Execute by saga name
        return sagaEngine.execute("order-processing", 
            StepInputs.of("orderId", request.getOrderId()))
            .map(this::mapToOrderResult);
    }
    
    // Execute by saga class
    public Mono<OrderResult> processOrderByClass(OrderRequest request) {
        return sagaEngine.execute(OrderProcessingSaga.class, 
            StepInputs.of("orderId", request.getOrderId()))
            .map(this::mapToOrderResult);
    }
    
    // Execute by method reference
    public Mono<OrderResult> processOrderByMethod(OrderRequest request) {
        return sagaEngine.execute(OrderProcessingSaga::validateOrder, 
            StepInputs.of("orderId", request.getOrderId()))
            .map(this::mapToOrderResult);
    }
}
```

### Advanced Input Building

```java
public Mono<SagaResult> processComplexOrder(OrderRequest request) {
    StepInputs inputs = StepInputs.builder()
        .forStepId("validate-order", request)
        .forStep(OrderProcessingSaga::reserveInventory, request.getItems())
        .forStepId("process-payment", ctx -> {
            // Lazy resolver - computed at execution time
            OrderValidation validation = ctx.getResult("validate-order");
            return PaymentRequest.from(validation);
        })
        .build();
    
    return sagaEngine.execute("order-processing", inputs);
}
```

### Custom Context

```java
public Mono<SagaResult> processOrderWithContext(OrderRequest request) {
    SagaContext context = new SagaContext("order-" + request.getOrderId());
    context.setHeader("X-User-Id", request.getUserId());
    context.setHeader("X-Tenant-Id", request.getTenantId());
    context.setVariable("region", request.getRegion());
    context.setVariable("priority", request.getPriority());
    
    return sagaEngine.execute("order-processing", inputs, context);
}
```

## Step Expansion

Dynamically generate steps for collections:

```java
@Service
public class BulkOrderService {
    
    public Mono<SagaResult> processBulkOrder(List<OrderItem> items) {
        StepInputs inputs = StepInputs.builder()
            .forStepId("process-items", ExpandEach.of(items)
                .withIdSuffix(item -> ":" + item.getId())
                .withInputMapper(item -> ProcessItemRequest.from(item)))
            .build();
        
        return sagaEngine.execute("bulk-order-processing", inputs);
    }
}
```

## Result Analysis

### Accessing Results

```java
public void analyzeResult(SagaResult result) {
    if (result.isSuccess()) {
        // Access step results
        OrderValidation validation = result.resultOf("validate-order", OrderValidation.class)
            .orElseThrow();
        
        InventoryReservation reservation = result.resultOf("reserve-inventory", InventoryReservation.class)
            .orElseThrow();
        
        PaymentResult payment = result.resultOf("process-payment", PaymentResult.class)
            .orElseThrow();
        
        log.info("Order processed successfully: payment={}", payment.getId());
    } else {
        // Analyze failure
        List<String> failedSteps = result.failedSteps();
        List<String> compensatedSteps = result.compensatedSteps();
        
        log.error("Order processing failed. Failed steps: {}, Compensated: {}", 
            failedSteps, compensatedSteps);
        
        // Get failure details
        result.steps().forEach((stepId, stepResult) -> {
            if (stepResult.status() == StepStatus.FAILED) {
                log.error("Step {} failed: {}", stepId, stepResult.error());
            }
        });
    }
}
```

### Step Status

```java
public void checkStepStatuses(SagaResult result) {
    Map<String, StepResult> steps = result.steps();
    
    steps.forEach((stepId, stepResult) -> {
        switch (stepResult.status()) {
            case DONE -> log.info("Step {} completed successfully", stepId);
            case FAILED -> log.error("Step {} failed: {}", stepId, stepResult.error());
            case COMPENSATED -> log.warn("Step {} was compensated", stepId);
            case SKIPPED -> log.info("Step {} was skipped", stepId);
            case PENDING -> log.info("Step {} is pending", stepId);
        }
    });
}
```

## Error Handling

### Retry Configuration

```java
@SagaStep(id = "unreliable-step", retry = 5, backoffMs = 1000, jitter = true)
public Mono<Result> unreliableStep(@Input Request request) {
    return externalService.call(request)
        .onErrorResume(ConnectException.class, ex -> {
            log.warn("Connection failed, will retry: {}", ex.getMessage());
            return Mono.error(ex); // Let saga engine handle retry
        });
}
```

### Custom Error Handling

```java
@SagaStep(id = "error-handling-step")
public Mono<Result> stepWithErrorHandling(@Input Request request) {
    return externalService.call(request)
        .onErrorResume(BusinessException.class, ex -> {
            // Handle business errors without retrying
            return Mono.just(Result.failed(ex.getMessage()));
        })
        .onErrorResume(TechnicalException.class, ex -> {
            // Let saga engine retry technical errors
            return Mono.error(ex);
        });
}
```

## HTTP Integration

### HTTP Calls with Context Propagation

```java
@SagaStep(id = "call-external-service")
public Mono<ExternalResult> callExternalService(
        @Input ExternalRequest request, 
        SagaContext context) {
    
    return HttpCall.exchangeOrError(
        webClient.post()
            .uri("/external/api")
            .bodyValue(request),
        context,
        ExternalResult.class,
        ErrorResponse.class,
        (status, error) -> new ExternalServiceException(status, error.getMessage())
    );
}
```

### Header Propagation

```java
@SagaStep(id = "propagate-headers")
public Mono<Result> callWithHeaderPropagation(
        @Input Request request,
        SagaContext context) {
    
    return HttpCall.propagate(
        webClient.get().uri("/service"), 
        context,
        Map.of("X-Custom-Header", "value")
    ).retrieve().bodyToMono(Result.class);
}
```

## Testing

### Unit Testing

```java
@ExtendWith(MockitoExtension.class)
class OrderProcessingSagaTest {
    
    @Mock
    private OrderService orderService;
    
    @Mock
    private InventoryService inventoryService;
    
    @Mock
    private PaymentService paymentService;
    
    @InjectMocks
    private OrderProcessingSaga saga;
    
    @Test
    void shouldValidateOrder() {
        // Given
        when(orderService.validate("order-123"))
            .thenReturn(Mono.just(new OrderValidation("order-123", true)));
        
        // When
        Mono<OrderValidation> result = saga.validateOrder("order-123");
        
        // Then
        StepVerifier.create(result)
            .assertNext(validation -> {
                assertEquals("order-123", validation.getOrderId());
                assertTrue(validation.isValid());
            })
            .verifyComplete();
    }
}
```

### Integration Testing

```java
@SpringBootTest
@EnableTransactionalEngine
class OrderProcessingSagaIntegrationTest {
    
    @Autowired
    private SagaEngine sagaEngine;
    
    @Test
    void shouldProcessOrderSuccessfully() {
        // Given
        StepInputs inputs = StepInputs.of("orderId", "order-123");
        
        // When
        SagaResult result = sagaEngine.execute("order-processing", inputs).block();
        
        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("order-processing", result.sagaName());
        
        // Verify step results
        assertTrue(result.resultOf("validate-order", OrderValidation.class).isPresent());
        assertTrue(result.resultOf("reserve-inventory", InventoryReservation.class).isPresent());
        assertTrue(result.resultOf("process-payment", PaymentResult.class).isPresent());
    }
}
```

## Best Practices

### 1. Design for Idempotency

```java
@SagaStep(id = "idempotent-step", idempotencyKey = "order-${orderId}")
public Mono<Result> idempotentStep(@Input("orderId") String orderId) {
    return service.processIdempotently(orderId);
}
```

### 2. Handle Partial Failures

```java
@SagaStep(id = "robust-step")
public Mono<Result> robustStep(@Input Request request) {
    return service.process(request)
        .onErrorResume(PartialFailureException.class, ex -> {
            // Handle partial success scenarios
            return Mono.just(Result.partial(ex.getSuccessfulItems()));
        });
}
```

### 3. Use Meaningful Step IDs

```java
// Good: descriptive step IDs
@SagaStep(id = "validate-customer-credit-limit")
@SagaStep(id = "reserve-inventory-for-order")
@SagaStep(id = "charge-customer-payment-method")

// Bad: generic step IDs
@SagaStep(id = "step1")
@SagaStep(id = "step2")
@SagaStep(id = "step3")
```

### 4. Design Effective Compensations

```java
// Store enough information for compensation
@SagaStep(id = "reserve-funds", compensate = "releaseFunds")
public Mono<FundReservation> reserveFunds(@Input ReserveRequest request) {
    return fundService.reserve(request)
        .doOnNext(reservation -> {
            // Store reservation details for compensation
            log.info("Reserved funds: reservationId={}, amount={}",
                reservation.getId(), reservation.getAmount());
        });
}

public Mono<Void> releaseFunds(@FromStep("reserve-funds") FundReservation reservation) {
    return fundService.release(reservation.getId())
        .doOnSuccess(v -> log.info("Released funds: reservationId={}", reservation.getId()));
}
```

### 5. Monitor and Observe

```java
@Component
public class OrderSagaEvents implements SagaEvents {

    @Override
    public void onStepSuccess(String sagaName, String sagaId, String stepId,
                             int attempts, long latencyMs) {
        meterRegistry.counter("saga.step.success",
            "saga", sagaName, "step", stepId).increment();

        meterRegistry.timer("saga.step.duration",
            "saga", sagaName, "step", stepId)
            .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onStepFailed(String sagaName, String sagaId, String stepId,
                            Throwable error, int attempts, long latencyMs) {
        meterRegistry.counter("saga.step.failure",
            "saga", sagaName, "step", stepId, "error", error.getClass().getSimpleName())
            .increment();
    }
}
```

## Common Patterns

### 1. Conditional Execution

```java
@SagaStep(id = "conditional-step")
public Mono<Result> conditionalStep(@Input Request request, SagaContext context) {
    if (request.isPremiumCustomer()) {
        return premiumService.process(request);
    } else {
        return standardService.process(request);
    }
}
```

### 2. Parallel Processing

```java
// Steps with no dependencies run in parallel
@SagaStep(id = "parallel-step-1")
public Mono<Result1> parallelStep1(@Input Request request) {
    return service1.process(request);
}

@SagaStep(id = "parallel-step-2")
public Mono<Result2> parallelStep2(@Input Request request) {
    return service2.process(request);
}

@SagaStep(id = "combine-results", dependsOn = {"parallel-step-1", "parallel-step-2"})
public Mono<CombinedResult> combineResults(
        @FromStep("parallel-step-1") Result1 result1,
        @FromStep("parallel-step-2") Result2 result2) {
    return Mono.just(CombinedResult.from(result1, result2));
}
```

### 3. Data Transformation

```java
@SagaStep(id = "transform-data")
public Mono<TransformedData> transformData(@Input RawData data) {
    return Mono.fromCallable(() -> {
        // CPU-bound transformation
        return dataTransformer.transform(data);
    }).subscribeOn(Schedulers.boundedElastic());
}
```
