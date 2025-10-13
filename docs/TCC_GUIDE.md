# TCC Pattern Guide

This guide provides comprehensive documentation for using the TCC (Try-Confirm-Cancel) pattern in the Firefly Transactional Engine.

## Overview

The TCC pattern implements distributed transactions through a two-phase commit protocol with resource reservation. Each participant implements three methods: Try (reserve resources), Confirm (commit reservation), and Cancel (release reservation).

## Key Concepts

### TCC Characteristics

- **Strong Consistency**: All participants commit or all rollback
- **Resource Reservation**: Lock resources in try phase
- **Two-Phase Commit**: Try → Confirm/Cancel phases
- **Isolation**: Better isolation than SAGA pattern

### When to Use TCC

✅ **Good for**:
- Financial transactions requiring strong consistency
- Resource reservation scenarios
- Short-lived transactions
- When isolation is critical

❌ **Avoid when**:
- Long-running processes
- Eventual consistency is acceptable
- Complex compensation logic is difficult

## Basic TCC Definition

### Complete TCC Transaction

```java
@Component
@Tcc(name = "OrderProcessing", timeoutMs = 30000)
public class OrderProcessingTcc {

    // Participant 1: Payment
    @TccParticipant(id = "payment", order = 1)
    public static class PaymentParticipant {

        @Autowired
        private PaymentService paymentService;

        @TryMethod(timeoutMs = 5000)
        public Mono<PaymentReservation> reservePayment(OrderRequest order) {
            return paymentService.reserveAmount(
                order.getCustomerId(),
                order.getTotalAmount()
            );
        }

        @ConfirmMethod(timeoutMs = 3000)
        public Mono<Void> confirmPayment(@FromTry PaymentReservation reservation) {
            return paymentService.captureReservation(reservation.getReservationId());
        }

        @CancelMethod(timeoutMs = 3000)
        public Mono<Void> cancelPayment(@FromTry PaymentReservation reservation) {
            return paymentService.releasePayment(reservation.getReservationId());
        }
    }

    // Participant 2: Inventory
    @TccParticipant(id = "inventory", order = 2)
    public static class InventoryParticipant {

        @Autowired
        private InventoryService inventoryService;

        @TryMethod(timeoutMs = 5000)
        public Mono<InventoryReservation> reserveInventory(OrderRequest order) {
            return inventoryService.reserveItems(order.getItems());
        }

        @ConfirmMethod(timeoutMs = 3000)
        public Mono<Void> confirmInventory(@FromTry InventoryReservation reservation) {
            return inventoryService.commitReservation(reservation.getReservationId());
        }

        @CancelMethod(timeoutMs = 3000)
        public Mono<Void> cancelInventory(@FromTry InventoryReservation reservation) {
            return inventoryService.releaseReservation(reservation.getReservationId());
        }
    }

    // Participant 3: Shipping
    @TccParticipant(id = "shipping", order = 3)
    public static class ShippingParticipant {

        @Autowired
        private ShippingService shippingService;

        @TryMethod(timeoutMs = 5000)
        public Mono<ShippingReservation> reserveShipping(OrderRequest order) {
            return shippingService.reserveCapacity(
                order.getShippingAddress(),
                order.getItems()
            );
        }

        @ConfirmMethod(timeoutMs = 3000)
        public Mono<Void> confirmShipping(@FromTry ShippingReservation reservation) {
            return shippingService.scheduleShipment(reservation.getReservationId());
        }

        @CancelMethod(timeoutMs = 3000)
        public Mono<Void> cancelShipping(@FromTry ShippingReservation reservation) {
            return shippingService.releaseCapacity(reservation.getReservationId());
        }
    }
}
```

## TCC Execution

### Basic Execution

```java
@Service
public class OrderService {

    @Autowired
    private TccEngine tccEngine;

    public Mono<OrderResult> processOrder(OrderRequest order) {
        // Build inputs for all participants
        TccInputs inputs = TccInputs.builder()
            .forParticipant("payment", order)
            .forParticipant("inventory", order)
            .forParticipant("shipping", order)
            .build();

        // Execute TCC transaction
        return tccEngine.execute("OrderProcessing", inputs)
            .map(result -> mapToOrderResult(order, result));
    }

    private OrderResult mapToOrderResult(OrderRequest order, TccResult result) {
        if (result.isSuccess() && result.isConfirmed()) {
            // All participants confirmed successfully
            PaymentReservation payment = result.getTryResult("payment");
            InventoryReservation inventory = result.getTryResult("inventory");
            ShippingReservation shipping = result.getTryResult("shipping");

            return OrderResult.success(
                order.getOrderId(),
                payment.getReservationId(),
                inventory.getReservationId(),
                shipping.getReservationId()
            );
        } else {
            // Transaction was canceled
            return OrderResult.failure(
                order.getOrderId(),
                result.getErrorMessage()
            );
        }
    }
}
```

### Advanced Input Building

```java
public Mono<OrderResult> processOrderWithCustomInputs(OrderRequest order) {
    TccInputs inputs = TccInputs.builder()
        .forParticipant("payment", PaymentRequest.from(order))
        .forParticipant("inventory", InventoryRequest.from(order))
        .forParticipant("shipping", ShippingRequest.from(order))
        .withGlobalTimeout(Duration.ofSeconds(45))
        .withContext(createOrderContext(order))
        .build();

    return tccEngine.execute("OrderProcessing", inputs)
        .map(result -> mapToOrderResult(order, result));
}

private TccContext createOrderContext(OrderRequest order) {
    TccContext context = new TccContext("order-" + order.getOrderId());
    context.putHeader("X-Customer-Id", order.getCustomerId());
    context.putHeader("X-Order-Type", order.getOrderType());
    context.putVariable("region", order.getRegion());
    return context;
}
```

## Participant Configuration

### Method-Level Configuration

```java
@TccParticipant(id = "payment", order = 1)
public static class PaymentParticipant {

    @TryMethod(
        timeoutMs = 10000,           // Method-specific timeout
        retry = 3,                   // Retry attempts
        backoffMs = 1000            // Backoff between retries
    )
    public Mono<PaymentReservation> reservePayment(OrderRequest order) {
        return paymentService.reserveAmount(order.getCustomerId(), order.getTotalAmount())
            .timeout(Duration.ofSeconds(8))  // Additional timeout
            .retryWhen(Retry.backoff(2, Duration.ofMillis(500))); // Additional retry
    }

    @ConfirmMethod(timeoutMs = 5000, retry = 2)
    public Mono<Void> confirmPayment(@FromTry PaymentReservation reservation) {
        return paymentService.capturePayment(reservation.getId());
    }

    @CancelMethod(timeoutMs = 5000, retry = 5) // More retries for cancel
    public Mono<Void> cancelPayment(@FromTry PaymentReservation reservation) {
        return paymentService.releasePayment(reservation.getId());
    }
}
```

### Parameter Injection

TCC methods support various parameter injection annotations for accessing context data:

```java
@TccParticipant(id = "enhanced-payment")
public static class EnhancedPaymentParticipant {

    @TryMethod
    public Mono<PaymentReservation> reservePayment(
            @Input OrderRequest order,                           // Full input object
            @Header("X-Customer-Id") String customerId,         // Header value
            @Input("region") String region,                     // Specific input field
            TccContext context) {                               // Full context

        return paymentService.reserveAmount(customerId, order.getTotalAmount(), region);
    }

    @ConfirmMethod
    public Mono<Void> confirmPayment(
            @FromTry PaymentReservation reservation,            // Try result
            @Header("X-Customer-Id") String customerId,
            TccContext context) {

        return paymentService.capturePayment(reservation.getId(), customerId);
    }

    @CancelMethod
    public Mono<Void> cancelPayment(
            @FromTry PaymentReservation reservation,
            TccContext context) {
        
        return paymentService.releasePayment(reservation.getId());
    }
}
```

## TCC Parameter Annotations

The TCC pattern provides specialized annotations for parameter injection in participant methods:

### @FromTry

Injects the result from the Try phase into Confirm or Cancel method parameters.

```java
@ConfirmMethod
public Mono<Void> confirmPayment(@FromTry PaymentReservation reservation) {
    // reservation contains the result from tryPayment method
    return paymentService.capturePayment(reservation.getId());
}

@CancelMethod
public Mono<Void> cancelPayment(@FromTry PaymentReservation reservation) {
    // Same reservation object from try phase
    return paymentService.releasePayment(reservation.getId());
}
```

**Key Features:**
- Automatically injects the participant's own try result
- Type-safe - matches the return type of the try method
- Available in both confirm and cancel methods
- Ensures each participant gets its own try result (isolated)

### @Header

Injects header values from the TCC context.

```java
@TryMethod
public Mono<PaymentReservation> reservePayment(
        @Input OrderRequest order,
        @Header("X-Customer-Id") String customerId,
        @Header(value = "X-Trace-Id", required = false) String traceId) {

    return paymentService.reserveAmount(customerId, order.getTotalAmount());
}
```

**Properties:**
- `value`: Header name to inject
- `required`: Whether the header is required (default: true)

### @Input

Injects input data for TCC participant methods.

```java
@TryMethod
public Mono<PaymentReservation> reservePayment(
        @Input OrderRequest order,              // Entire input object
        @Input("customerId") String customerId, // Specific field from Map input
        @Input("amount") BigDecimal amount) {   // Another specific field

    return paymentService.reserveAmount(customerId, amount);
}
```

**Properties:**
- `value`: Key to extract from Map input (empty = entire input object)
- `required`: Whether the input is required (default: true)

**Usage Patterns:**
- No `value`: Injects the entire input object
- With `value`: Extracts specific field from Map input
- Works with both object inputs and Map inputs

## Result Analysis

### Accessing TCC Results

```java
public void analyzeTccResult(TccResult result) {
    if (result.isSuccess()) {
        if (result.isConfirmed()) {
            log.info("TCC transaction confirmed successfully");
            
            // Access try results
            PaymentReservation payment = result.getTryResult("payment");
            InventoryReservation inventory = result.getTryResult("inventory");
            ShippingReservation shipping = result.getTryResult("shipping");
            
            log.info("Reservations - Payment: {}, Inventory: {}, Shipping: {}", 
                payment.getId(), inventory.getId(), shipping.getId());
        } else if (result.isCanceled()) {
            log.warn("TCC transaction was canceled");
            
            // Check which participants succeeded in try phase
            result.getParticipantResults().forEach((participantId, participantResult) -> {
                if (participantResult.isTrySucceeded()) {
                    log.info("Participant {} try succeeded but was canceled", participantId);
                }
            });
        }
    } else {
        log.error("TCC transaction failed: {}", result.getError());
        
        // Analyze participant failures
        result.getParticipantResults().forEach((participantId, participantResult) -> {
            if (participantResult.isTryFailed()) {
                log.error("Participant {} try failed: {}", participantId, participantResult.getTryError());
            }
            if (participantResult.isConfirmFailed()) {
                log.error("Participant {} confirm failed: {}", participantId, participantResult.getConfirmError());
            }
            if (participantResult.isCancelFailed()) {
                log.error("Participant {} cancel failed: {}", participantId, participantResult.getCancelError());
            }
        });
    }
}
```

### Participant Status

```java
public void checkParticipantStatus(TccResult result) {
    result.getParticipantResults().forEach((participantId, participantResult) -> {
        log.info("Participant {}: try={}, confirm={}, cancel={}", 
            participantId,
            participantResult.getTryStatus(),
            participantResult.getConfirmStatus(),
            participantResult.getCancelStatus());
        
        // Check specific statuses
        if (participantResult.isTrySucceeded()) {
            Object tryResult = participantResult.getTryResult();
            log.info("Participant {} try result: {}", participantId, tryResult);
        }
    });
}
```

## Error Handling

### Retry Configuration

```java
@TccParticipant(id = "resilient-payment")
public static class ResilientPaymentParticipant {

    @TryMethod(retry = 5, backoffMs = 2000)
    public Mono<PaymentReservation> reservePayment(OrderRequest order) {
        return paymentService.reserveAmount(order.getCustomerId(), order.getTotalAmount())
            .onErrorResume(ConnectException.class, ex -> {
                log.warn("Connection failed, will retry: {}", ex.getMessage());
                return Mono.error(ex); // Let TCC engine handle retry
            });
    }

    @ConfirmMethod(retry = 3, backoffMs = 1000)
    public Mono<Void> confirmPayment(@FromTry PaymentReservation reservation) {
        return paymentService.capturePayment(reservation.getId())
            .onErrorResume(OptimisticLockException.class, ex -> {
                log.warn("Optimistic lock failed, will retry: {}", ex.getMessage());
                return Mono.error(ex);
            });
    }

    @CancelMethod(retry = 10, backoffMs = 500) // Aggressive retry for cancel
    public Mono<Void> cancelPayment(@FromTry PaymentReservation reservation) {
        return paymentService.releasePayment(reservation.getId())
            .onErrorResume(ex -> {
                log.error("Cancel failed, will retry: {}", ex.getMessage());
                return Mono.error(ex);
            });
    }
}
```

### Custom Error Handling

```java
@TryMethod
public Mono<PaymentReservation> reservePaymentWithErrorHandling(OrderRequest order) {
    return paymentService.reserveAmount(order.getCustomerId(), order.getTotalAmount())
        .onErrorResume(InsufficientFundsException.class, ex -> {
            // Business error - don't retry, fail immediately
            log.warn("Insufficient funds for customer {}: {}", order.getCustomerId(), ex.getMessage());
            return Mono.error(new TccBusinessException("Insufficient funds", ex));
        })
        .onErrorResume(PaymentServiceUnavailableException.class, ex -> {
            // Technical error - let TCC engine retry
            log.warn("Payment service unavailable, will retry: {}", ex.getMessage());
            return Mono.error(ex);
        });
}
```

## Testing

### Unit Testing

```java
@ExtendWith(MockitoExtension.class)
class OrderProcessingTccTest {
    
    @Mock
    private PaymentService paymentService;
    
    @Mock
    private InventoryService inventoryService;
    
    @Mock
    private ShippingService shippingService;
    
    @Test
    void shouldReservePaymentSuccessfully() {
        // Given
        OrderRequest order = createTestOrder();
        PaymentReservation expectedReservation = new PaymentReservation("res-123", order.getTotalAmount());
        
        when(paymentService.reserveAmount(order.getCustomerId(), order.getTotalAmount()))
            .thenReturn(Mono.just(expectedReservation));
        
        // When
        OrderProcessingTcc.PaymentParticipant participant = new OrderProcessingTcc.PaymentParticipant();
        ReflectionTestUtils.setField(participant, "paymentService", paymentService);
        
        Mono<PaymentReservation> result = participant.reservePayment(order);
        
        // Then
        StepVerifier.create(result)
            .assertNext(reservation -> {
                assertEquals("res-123", reservation.getId());
                assertEquals(order.getTotalAmount(), reservation.getAmount());
            })
            .verifyComplete();
    }
}
```

### Integration Testing

```java
@SpringBootTest
@EnableTransactionalEngine
class OrderProcessingTccIntegrationTest {
    
    @Autowired
    private TccEngine tccEngine;
    
    @Test
    void shouldProcessOrderSuccessfully() {
        // Given
        OrderRequest order = createTestOrder();
        TccInputs inputs = TccInputs.builder()
            .forParticipant("payment", order)
            .forParticipant("inventory", order)
            .forParticipant("shipping", order)
            .build();
        
        // When
        TccResult result = tccEngine.execute("OrderProcessing", inputs).block();
        
        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertTrue(result.isConfirmed());
        assertEquals(TccPhase.CONFIRM, result.getFinalPhase());
        
        // Verify participant results
        assertTrue(result.getParticipantResult("payment").isTrySucceeded());
        assertTrue(result.getParticipantResult("payment").isConfirmSucceeded());
        
        assertTrue(result.getParticipantResult("inventory").isTrySucceeded());
        assertTrue(result.getParticipantResult("inventory").isConfirmSucceeded());
        
        assertTrue(result.getParticipantResult("shipping").isTrySucceeded());
        assertTrue(result.getParticipantResult("shipping").isConfirmSucceeded());
    }
    
    @Test
    void shouldCancelWhenParticipantFails() {
        // Given
        OrderRequest order = createTestOrderWithInsufficientFunds();
        TccInputs inputs = TccInputs.builder()
            .forParticipant("payment", order)
            .forParticipant("inventory", order)
            .forParticipant("shipping", order)
            .build();
        
        // When
        TccResult result = tccEngine.execute("OrderProcessing", inputs).block();
        
        // Then
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.isCanceled());
        assertEquals(TccPhase.CANCEL, result.getFinalPhase());
        
        // Verify payment failed but others were canceled
        assertTrue(result.getParticipantResult("payment").isTryFailed());
        
        // Other participants should have been canceled if they succeeded
        TccParticipantResult inventoryResult = result.getParticipantResult("inventory");
        if (inventoryResult.isTrySucceeded()) {
            assertTrue(inventoryResult.isCancelSucceeded());
        }
    }
}

## Best Practices

### 1. Design Idempotent Operations

```java
@TryMethod
public Mono<PaymentReservation> reservePayment(OrderRequest order) {
    // Use idempotency key to handle duplicate requests
    String idempotencyKey = "reserve-" + order.getOrderId() + "-" + order.getCustomerId();

    return paymentService.reserveAmountIdempotent(
        order.getCustomerId(),
        order.getTotalAmount(),
        idempotencyKey
    );
}

@ConfirmMethod
public Mono<Void> confirmPayment(@FromTry PaymentReservation reservation) {
    // Confirm operations should be idempotent
    return paymentService.capturePaymentIdempotent(reservation.getId());
}

@CancelMethod
public Mono<Void> cancelPayment(@FromTry PaymentReservation reservation) {
    // Cancel operations must be idempotent
    return paymentService.releasePaymentIdempotent(reservation.getId());
}
```

### 2. Handle Resource Timeouts

```java
@TccParticipant(id = "inventory-with-timeout")
public static class InventoryParticipantWithTimeout {

    @TryMethod(timeoutMs = 10000)
    public Mono<InventoryReservation> reserveInventory(OrderRequest order) {
        return inventoryService.reserveItems(order.getItems())
            .timeout(Duration.ofSeconds(8)) // Shorter than TCC timeout
            .doOnNext(reservation -> {
                // Set expiration time for reservation
                reservation.setExpiresAt(Instant.now().plus(Duration.ofMinutes(5)));
            });
    }

    @ConfirmMethod(timeoutMs = 5000)
    public Mono<Void> confirmInventory(@FromTry InventoryReservation reservation) {
        // Check if reservation is still valid
        if (reservation.isExpired()) {
            return Mono.error(new ReservationExpiredException(
                "Inventory reservation expired: " + reservation.getId()));
        }

        return inventoryService.commitReservation(reservation.getId());
    }

    @CancelMethod(timeoutMs = 5000)
    public Mono<Void> cancelInventory(@FromTry InventoryReservation reservation) {
        // Always try to cancel, even if expired
        return inventoryService.releaseReservation(reservation.getId())
            .onErrorResume(ReservationNotFoundException.class, ex -> {
                // Reservation might have already expired and been cleaned up
                log.info("Reservation already released: {}", reservation.getId());
                return Mono.empty();
            });
    }
}
```

### 3. Implement Proper Ordering

```java
@Tcc(name = "OrderedTransaction")
public class OrderedTransactionTcc {

    // Order matters: payment first (most likely to fail)
    @TccParticipant(id = "payment", order = 1)
    public static class PaymentParticipant {
        // Payment logic
    }

    // Then inventory (second most likely to fail)
    @TccParticipant(id = "inventory", order = 2)
    public static class InventoryParticipant {
        // Inventory logic
    }

    // Finally shipping (least likely to fail)
    @TccParticipant(id = "shipping", order = 3)
    public static class ShippingParticipant {
        // Shipping logic
    }
}
```

### 4. Monitor and Observe

The TCC engine provides comprehensive event handling for monitoring and observability through two mechanisms:

#### TCC Lifecycle Events (TccEvents Interface)

Implement the `TccEvents` interface to receive lifecycle events:

```java
@Component
public class CustomTccEvents implements TccEvents {

    @Override
    public void onTccStarted(String tccName, String correlationId, TccContext context) {
        meterRegistry.counter("tcc.started", "tcc", tccName).increment();
    }

    @Override
    public void onTccCompleted(String tccName, String correlationId, boolean success,
                              TccPhase finalPhase, Duration duration) {
        meterRegistry.counter("tcc.completed",
            "tcc", tccName,
            "phase", finalPhase.name(),
            "success", String.valueOf(success)
        ).increment();

        meterRegistry.timer("tcc.duration", "tcc", tccName).record(duration);
    }

    @Override
    public void onParticipantFailed(String tccName, String correlationId, String participantId,
                                   TccPhase phase, Throwable error, int attempts, Duration duration) {
        meterRegistry.counter("tcc.participant.failed",
            "tcc", tccName,
            "participant", participantId,
            "phase", phase.name()
        ).increment();
    }
}
```

#### External Event Publishing (TccEventPublisher Interface)

For publishing events to external systems, implement the `TccEventPublisher` interface:

```java
@Component
public class KafkaTccEventPublisher implements TccEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public Mono<Void> publish(TccEventEnvelope event) {
        return Mono.fromRunnable(() -> {
            kafkaTemplate.send(event.getTopic(), event.getKey(), event.getPayload());
        });
    }
}
```

Then configure participants to publish events using the `@TccEvent` annotation:

```java
@TccParticipant(id = "payment")
@TccEvent(topic = "payment-events", eventType = "PAYMENT_PARTICIPANT", key = "#{correlationId}")
public class PaymentParticipant {
    // Participant methods...
}
```

### 5. Handle Partial Failures Gracefully

```java
@CancelMethod
public Mono<Void> cancelPaymentGracefully(@FromTry PaymentReservation reservation) {
    return paymentService.releasePayment(reservation.getId())
        .onErrorResume(PaymentNotFoundException.class, ex -> {
            // Payment might have already been released
            log.info("Payment reservation not found, assuming already released: {}",
                reservation.getId());
            return Mono.empty();
        })
        .onErrorResume(PaymentServiceUnavailableException.class, ex -> {
            // Service unavailable - log for manual intervention
            log.error("Unable to cancel payment reservation {}, manual intervention required",
                reservation.getId(), ex);

            // Don't fail the cancel - mark for manual review
            alertingService.sendAlert("Manual payment cancellation required",
                reservation.getId());
            return Mono.empty();
        });
}
```

## Common Patterns

### 1. Conditional Participation

```java
@TryMethod
public Mono<ShippingReservation> reserveShipping(OrderRequest order) {
    if (order.isDigitalOrder()) {
        // No shipping needed for digital orders
        return Mono.just(ShippingReservation.notRequired());
    }

    return shippingService.reserveCapacity(order.getShippingAddress(), order.getItems());
}
```

### 2. Nested Resource Management

```java
@TryMethod
public Mono<ComplexReservation> reserveComplexResource(OrderRequest order) {
    return Mono.zip(
        warehouseService.reserveSpace(order.getItems()),
        transportService.reserveVehicle(order.getShippingAddress()),
        driverService.reserveDriver(order.getDeliveryTime())
    ).map(tuple -> new ComplexReservation(tuple.getT1(), tuple.getT2(), tuple.getT3()));
}

@CancelMethod
public Mono<Void> cancelComplexResource(@FromTry ComplexReservation reservation) {
    return Mono.when(
        warehouseService.releaseSpace(reservation.getSpaceReservation()),
        transportService.releaseVehicle(reservation.getVehicleReservation()),
        driverService.releaseDriver(reservation.getDriverReservation())
    );
}
```

### 3. Compensation with Audit Trail

```java
@CancelMethod
public Mono<Void> cancelWithAudit(@FromTry PaymentReservation reservation, TccContext context) {
    return paymentService.releasePayment(reservation.getId())
        .doOnSuccess(v -> {
            auditService.recordCancellation(
                reservation.getId(),
                context.getCorrelationId(),
                "TCC_CANCEL",
                Instant.now()
            );
        })
        .doOnError(ex -> {
            auditService.recordCancellationFailure(
                reservation.getId(),
                context.getCorrelationId(),
                ex.getMessage(),
                Instant.now()
            );
        });
}
```

## TCC Compositor

The TCC Compositor enables orchestration of multiple TCC transactions with dependencies, parallel execution, and data flow between them.

### Basic Composition

```java
@Service
public class OrderWorkflowService {

    @Autowired
    private TccCompositor tccCompositor;

    public Mono<TccCompositionResult> processComplexOrder(OrderRequest request) {
        TccComposition composition = TccCompositor.compose("complex-order-workflow")
            .compensationPolicy(CompensationPolicy.STRICT_SEQUENTIAL)

            // Step 1: Payment processing
            .tcc("payment-processing")
                .withId("payment")
                .withInput("amount", request.getAmount())
                .withInput("customerId", request.getCustomerId())
                .add()

            // Step 2: Inventory reservation (depends on payment)
            .tcc("inventory-management")
                .withId("inventory")
                .dependsOn("payment")
                .withInput("productId", request.getProductId())
                .withInput("quantity", request.getQuantity())
                .add()

            // Step 3: Shipping arrangement (depends on inventory)
            .tcc("shipping-coordination")
                .withId("shipping")
                .dependsOn("inventory")
                .withInput("address", request.getShippingAddress())
                .add()

            .build();

        TccContext rootContext = new TccContext(request.getCorrelationId());
        return tccCompositor.execute(composition, rootContext);
    }
}
```

### Advanced Features

#### 1. Data Flow Between TCCs

```java
TccComposition composition = TccCompositor.compose("order-with-data-flow")
    .tcc("payment-processing")
        .withId("payment")
        .withInput("amount", request.getAmount())
        .add()

    .tcc("inventory-management")
        .withId("inventory")
        .dependsOn("payment")
        // Map payment transaction ID to inventory input
        .withDataFrom("payment", "payment-participant", "transactionId", "paymentTransactionId")
        .withInput("productId", request.getProductId())
        .add()

    .tcc("shipping-coordination")
        .withId("shipping")
        .dependsOn("inventory")
        // Map inventory reservation ID to shipping input
        .withDataFrom("inventory", "inventory-participant", "reservationId", "inventoryReservationId")
        .withInput("address", request.getShippingAddress())
        .add()

    .build();
```

#### 2. Parallel Execution

```java
TccComposition composition = TccCompositor.compose("parallel-processing")
    .compensationPolicy(CompensationPolicy.GROUPED_PARALLEL)

    .tcc("payment-processing")
        .withId("payment")
        .add()

    // These two run in parallel after payment
    .tcc("inventory-management")
        .withId("inventory")
        .dependsOn("payment")
        .add()

    .tcc("loyalty-points")
        .withId("loyalty")
        .dependsOn("payment")
        .executeInParallelWith("inventory")
        .add()

    .build();
```

#### 3. Optional TCCs

```java
TccComposition composition = TccCompositor.compose("resilient-order")
    .tcc("payment-processing")
        .withId("payment")
        .add()

    .tcc("inventory-management")
        .withId("inventory")
        .dependsOn("payment")
        .add()

    // Optional notification - won't fail the composition
    .tcc("notification-service")
        .withId("notification")
        .dependsOn("inventory")
        .optional()
        .add()

    .build();
```

#### 4. Conditional Execution

```java
TccComposition composition = TccCompositor.compose("conditional-workflow")
    .tcc("payment-processing")
        .withId("payment")
        .add()

    .tcc("premium-processing")
        .withId("premium")
        .dependsOn("payment")
        .when(context -> {
            // Only execute for premium customers
            return context.getSharedVariable("customerTier").equals("PREMIUM");
        })
        .add()

    .build();
```

### Compensation Policies

#### STRICT_SEQUENTIAL
Cancels TCCs one by one in reverse dependency order.

#### GROUPED_PARALLEL
Groups TCCs by dependency level and cancels each group in parallel.

#### BEST_EFFORT_PARALLEL
Attempts to cancel all TCCs in parallel, continuing even if some fail.

### Composition Result

```java
TccCompositionResult result = tccCompositor.execute(composition, context).block();

// Check overall success
if (result.isSuccess()) {
    System.out.println("All TCCs completed successfully");
} else {
    System.out.println("Composition failed: " + result.getCompositionError());
}

// Get individual TCC results
for (String tccId : result.getCompletedTccs()) {
    TccResult tccResult = result.getTccResult(tccId);
    System.out.println("TCC " + tccId + " completed in " + tccResult.getDuration());
}

// Get participant-specific results
Object paymentResult = result.getTccParticipantResult("payment", "payment-participant");
```

### Best Practices

1. **Keep compositions focused** - Don't create overly complex workflows
2. **Use appropriate compensation policies** - Choose based on your consistency requirements
3. **Handle optional TCCs carefully** - Mark non-critical operations as optional
4. **Design for idempotency** - All TCC methods should be idempotent
5. **Monitor composition performance** - Use the built-in observability features
6. **Test failure scenarios** - Ensure compensation works correctly

### Error Handling

The TCC Compositor automatically handles:
- Individual TCC failures with compensation
- Timeout management
- Dependency validation
- Circular dependency detection
- Data mapping validation

```java
// Custom error handling
tccCompositor.execute(composition, context)
    .doOnError(error -> {
        if (error instanceof TccCompositionException) {
            // Handle composition-specific errors
            log.error("Composition failed: {}", error.getMessage());
        }
    })
    .onErrorResume(error -> {
        // Fallback logic
        return handleCompositionFailure(error);
    });
```
