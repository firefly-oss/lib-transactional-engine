# Saga Composition Guide

The Firefly Transactional Engine's Saga Compositor provides a powerful way to orchestrate multiple sagas into coordinated workflows. This enables complex business processes that span multiple bounded contexts while maintaining the reliability and consistency guarantees of the saga pattern.

## Overview

The Saga Compositor allows you to:

- **Compose Multiple Sagas**: Combine existing saga definitions into higher-level workflows
- **Define Dependencies**: Specify execution order and dependencies between sagas
- **Enable Parallel Execution**: Run independent sagas concurrently for better performance
- **Share Data**: Pass data between sagas in the composition
- **Handle Failures**: Apply composition-level compensation strategies
- **Monitor Execution**: Get comprehensive observability across the entire composition

## Basic Usage

### Creating a Simple Sequential Composition

```java
@Service
public class OrderFulfillmentService {

    @Autowired
    private SagaCompositor sagaCompositor;

    public Mono<SagaCompositionResult> processOrder(OrderRequest request) {
        SagaComposition composition = SagaCompositor.compose("order-fulfillment")
            .saga("payment-processing")
                .withId("payment")
                .withInput("orderId", request.getOrderId())
                .withInput("amount", request.getAmount())
                .add()
            .saga("inventory-reservation")
                .withId("inventory")
                .dependsOn("payment")
                .withDataFrom("payment", "paymentId")
                .add()
            .saga("shipping-preparation")
                .withId("shipping")
                .dependsOn("inventory")
                .withDataFrom("inventory", "reservationId")
                .add()
            .build();

        SagaContext context = new SagaContext(request.getCorrelationId());
        return sagaCompositor.execute(composition, context);
    }
}
```

### Parallel Execution

```java
SagaComposition composition = SagaCompositor.compose("parallel-processing")
    .saga("payment-processing")
        .withId("payment")
        .withInput("orderId", orderId)
        .add()
    .saga("shipping-calculation")
        .withId("shipping")
        .dependsOn("payment")
        .executeInParallelWith("notifications")
        .add()
    .saga("notification-sending")
        .withId("notifications")
        .dependsOn("payment")
        .withDataFrom("payment", "paymentId")
        .add()
    .build();
```

## Advanced Features

### Data Flow Between Sagas

The compositor supports sophisticated data mapping between sagas:

```java
.saga("inventory-reservation")
    .withId("inventory")
    .dependsOn("payment")
    // Map specific fields with different target key
    .withDataFrom("payment", "paymentId", "paymentReference")
    // Map with same key name
    .withDataFrom("payment", "amount")
    .add()
```

Note: Data transformation during mapping is handled by the CompositionDataFlowManager internally. For complex transformations, use saga step logic or shared variables.

### Conditional Execution

```java
.saga("premium-shipping")
    .withId("premium")
    .dependsOn("payment")
    .executeIf(ctx -> {
        // Access saga results through the composition context
        SagaResult paymentResult = ctx.getSagaResult("payment");
        if (paymentResult != null && paymentResult.isSuccess()) {
            Double amount = (Double) paymentResult.getStepResult("process-payment");
            return amount != null && amount > 100.0;
        }
        return false;
    })
    .add()
```

### Optional Sagas

```java
.saga("loyalty-points")
    .withId("loyalty")
    .dependsOn("payment")
    .optional() // Won't fail the composition if it fails
    .timeout(5000) // 5 second timeout
    .add()
```

### Compensation Policies

```java
SagaComposition composition = SagaCompositor.compose("robust-processing")
    .compensationPolicy(CompensationPolicy.GROUPED_PARALLEL)
    .saga("payment-processing")
        .withId("payment")
        .add()
    .saga("inventory-reservation")
        .withId("inventory")
        .dependsOn("payment")
        .add()
    .build();
```

Available compensation policies:
- `STRICT_SEQUENTIAL`: Compensate in reverse completion order
- `GROUPED_PARALLEL`: Compensate by dependency layers in parallel
- `BEST_EFFORT_PARALLEL`: Parallel compensation, continue on errors
- `RETRY_WITH_BACKOFF`: Retry failed compensations with backoff
- `CIRCUIT_BREAKER`: Stop compensation after critical failures

## Configuration

### Spring Boot Configuration

```java
@Configuration
public class SagaCompositionConfig {
    
    @Bean
    public SagaCompositor sagaCompositor(SagaEngine sagaEngine, 
                                        SagaRegistry sagaRegistry,
                                        SagaEvents sagaEvents) {
        return new SagaCompositor(sagaEngine, sagaRegistry, sagaEvents);
    }
}
```

### Custom Event Handling

```java
@Component
public class CompositionEventHandler implements SagaEvents {

    @Override
    public void onCompositionStarted(String compositionName, String compositionId) {
        log.info("Started composition: {} with ID: {}", compositionName, compositionId);
    }

    @Override
    public void onCompositionSagaStarted(String compositionName, String compositionId,
                                       String sagaId, String sagaName) {
        log.info("Saga {} ({}) started in composition {}", sagaId, sagaName, compositionName);
    }

    @Override
    public void onCompositionCompleted(String compositionName, String compositionId,
                                     boolean success, long latencyMs,
                                     int completedSagas, int failedSagas, int skippedSagas) {
        log.info("Composition {} completed: success={}, duration={}ms, completed={}, failed={}, skipped={}",
                compositionName, success, latencyMs, completedSagas, failedSagas, skippedSagas);
    }

    @Override
    public void onCompositionFailed(String compositionName, String compositionId,
                                  Throwable error, long latencyMs) {
        log.error("Composition {} failed after {}ms", compositionName, latencyMs, error);
    }
}
```

## Best Practices

### 1. Design for Idempotency

Ensure all sagas in your composition are idempotent:

```java
@SagaStep(id = "process-payment", idempotencyKey = "payment-${orderId}")
public Mono<PaymentResult> processPayment(@Input("orderId") String orderId) {
    // Implementation should be idempotent
}
```

### 2. Use Meaningful Saga IDs

```java
.saga("payment-processing")
    .withId("payment") // Use short, meaningful IDs
    .add()
.saga("inventory-reservation")
    .withId("inventory")
    .dependsOn("payment") // Reference by meaningful ID
    .add()
```

The `withId()` method sets the composition-specific ID that's used for dependencies and data mapping, while the saga name (first parameter) refers to the registered saga definition.

### 3. Handle Failures Gracefully

```java
.saga("audit-logging")
    .withId("audit")
    .dependsOn("payment")
    .optional() // Don't fail the entire process for audit failures
    .add()
```

### 4. Monitor Composition Performance

```java
SagaCompositionResult result = sagaCompositor.execute(composition, context).block();

log.info("Composition '{}' completed in {}ms: success={}, completed={}, failed={}, skipped={}",
         result.getCompositionName(),
         result.getDuration().toMillis(),
         result.isSuccess(),
         result.getCompletedSagaCount(),
         result.getFailedSagaCount(),
         result.getSkippedSagaCount());
```

### 5. Use Shared Variables for Cross-Saga Communication

```java
// In a saga step - use the composition context
ctx.putVariable("shared.customerId", customerId);

// Later accessed in another saga through the composition context
String customerId = (String) ctx.getVariable("shared.customerId");

// Or access through the composition result
Map<String, Object> sharedVars = result.getSharedVariables();
String customerId = (String) sharedVars.get("shared.customerId");
```

## Error Handling

### Composition-Level Errors

```java
sagaCompositor.execute(composition, context)
    .doOnError(error -> {
        log.error("Composition failed", error);
        // Handle composition-level failure
    })
    .onErrorResume(error -> {
        // Fallback logic
        return handleCompositionFailure(error);
    });
```

### Individual Saga Failures

```java
SagaCompositionResult result = // ... execute composition

if (!result.isSuccess()) {
    for (String failedSagaId : result.getFailedSagas()) {
        Throwable error = result.getSagaErrors().get(failedSagaId);
        log.error("Saga {} failed: {}", failedSagaId, error.getMessage());
    }
}
```

## Testing

### Unit Testing Compositions

```java
@Test
void testOrderFulfillmentComposition() {
    SagaComposition composition = SagaCompositor.compose("test-order")
        .saga("payment-processing")
            .withId("payment")
            .withInput("orderId", "test-order-123")
            .add()
        .saga("inventory-reservation")
            .withId("inventory")
            .dependsOn("payment")
            .add()
        .build();

    // Validate composition structure
    sagaCompositor.validate(composition);

    // Execute and verify
    StepVerifier.create(sagaCompositor.execute(composition, new SagaContext("test")))
        .assertNext(result -> {
            assertTrue(result.isSuccess());
            assertEquals(2, result.getCompletedSagaCount());
            assertEquals(0, result.getFailedSagaCount());
            assertTrue(result.isSagaCompleted("payment"));
            assertTrue(result.isSagaCompleted("inventory"));
        })
        .verifyComplete();
}
```

### Integration Testing

```java
@SpringBootTest
@TestPropertySource(properties = {
    "saga.engine.compensation-policy=STRICT_SEQUENTIAL"
})
class CompositionIntegrationTest {
    
    @Autowired
    private SagaCompositor sagaCompositor;
    
    @Test
    void testRealWorldComposition() {
        // Test with real saga implementations
    }
}
```

## Migration from Individual Sagas

If you have existing individual saga executions, you can gradually migrate to compositions:

```java
// Before: Individual saga execution
sagaEngine.execute("payment-processing", inputs, context)
    .flatMap(paymentResult -> 
        sagaEngine.execute("inventory-reservation", inventoryInputs, context))
    .flatMap(inventoryResult -> 
        sagaEngine.execute("shipping-preparation", shippingInputs, context));

// After: Composition
SagaComposition composition = SagaCompositor.compose("order-fulfillment")
    .saga("payment-processing")
        .withId("payment")
        .add()
    .saga("inventory-reservation")
        .withId("inventory")
        .dependsOn("payment")
        .add()
    .saga("shipping-preparation")
        .withId("shipping")
        .dependsOn("inventory")
        .add()
    .build();

sagaCompositor.execute(composition, context);
```

## Performance Considerations

- **Parallel Execution**: Use `executeInParallelWith()` for independent sagas
- **Timeouts**: Set appropriate timeouts for long-running sagas
- **Resource Management**: Monitor memory usage for large compositions
- **Compensation Strategy**: Choose the right compensation policy for your use case

## Troubleshooting

### Common Issues

1. **Circular Dependencies**: Ensure no saga depends on itself transitively
2. **Missing Dependencies**: Verify all referenced sagas exist in the registry
3. **Data Mapping Errors**: Check that source sagas complete before dependent sagas execute
4. **Timeout Issues**: Adjust timeouts for slow sagas or network calls

### Debugging

Enable debug logging to trace composition execution:

```properties
logging.level.com.firefly.transactional.composition=DEBUG
```

This will provide detailed logs of saga execution order, data flow, and timing information.

## API Reference

### Key Classes

- **`SagaCompositor`**: Main entry point for creating and executing saga compositions
- **`SagaComposition`**: Immutable definition of a saga composition workflow
- **`SagaCompositionBuilder`**: Fluent builder for creating saga compositions
- **`SagaCompositionResult`**: Result of composition execution with detailed metrics
- **`SagaCompositionContext`**: Runtime context for composition execution
- **`CompositionValidator`**: Validates composition structure and dependencies

### Main Methods

- `SagaCompositor.compose(String name)`: Creates a new composition builder
- `SagaCompositor.execute(SagaComposition, SagaContext)`: Executes a composition
- `SagaCompositor.validate(SagaComposition)`: Validates composition structure
- `SagaCompositionBuilder.saga(String sagaName)`: Adds a saga to the composition
- `SagaBuilder.withId(String id)`: Sets composition-specific saga ID
- `SagaBuilder.dependsOn(String sagaId)`: Adds dependency on another saga
- `SagaBuilder.executeInParallelWith(String sagaId)`: Declares parallel execution
- `SagaBuilder.withDataFrom(String sourceSaga, String key)`: Maps data between sagas
- `SagaBuilder.executeIf(Function<SagaCompositionContext, Boolean>)`: Conditional execution
- `SagaBuilder.optional()`: Marks saga as optional (won't fail composition)
- `SagaBuilder.timeout(int ms)`: Sets saga timeout
