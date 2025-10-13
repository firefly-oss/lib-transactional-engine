# Firefly Transactional Engine

A high-performance, reactive distributed transaction engine for Spring Boot 3 applications. Provides both SAGA and TCC (Try-Confirm-Cancel) patterns for managing distributed transactions with comprehensive persistence, recovery, and observability features.

## Key Features

- **Dual Transaction Patterns**: SAGA (eventual consistency) and TCC (strong consistency)
- **Pattern Isolation**: Complete infrastructure isolation between SAGA and TCC patterns
- **Reactive Architecture**: Built on Project Reactor for non-blocking operations
- **Flexible Persistence**: In-memory by default with optional Redis persistence
- **Automatic Recovery**: Built-in recovery mechanisms for application restarts
- **Saga Composition**: Orchestrate multiple sagas into coordinated workflows
- **Comprehensive Event System**: Built-in observability and external event publishing for both SAGA and TCC patterns
- **Spring Boot Integration**: Auto-configuration with comprehensive property support
- **Comprehensive Testing**: 100% test coverage with integration tests
- **Type-Safe API**: Method reference support with compile-time validation

## Quick Start

### Add Dependency

```xml
<dependency>
    <groupId>com.firefly</groupId>
    <artifactId>lib-transactional-engine</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Enable the Engine

```java
@SpringBootApplication
@EnableTransactionalEngine
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### Define a SAGA

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

### Execute SAGA

```java
@RestController
public class OrderController {
    
    @Autowired
    private SagaEngine sagaEngine;
    
    @PostMapping("/orders")
    public Mono<SagaResult> createOrder(@RequestBody CreateOrderRequest request) {
        return sagaEngine.execute("order-processing", 
            StepInputs.of("orderId", request.getOrderId()));
    }
}
```

## Transaction Patterns

### SAGA Pattern

**Best for**: Long-running transactions, eventual consistency, microservices orchestration

The SAGA pattern implements distributed transactions through a sequence of local transactions with compensating actions:

```java
@Saga(name = "payment-processing")
public class PaymentSaga {

    @SagaStep(id = "charge", compensate = "refund")
    public Mono<PaymentResult> processPayment(OrderRequest order) {
        return paymentService.charge(order.getAmount());
    }

    public Mono<Void> refund(@FromStep("charge") PaymentResult payment) {
        return paymentService.refund(payment.getTransactionId());
    }
}
```

**Characteristics**:
- ✅ Forward execution with compensation on failure
- ✅ Eventual consistency
- ✅ Simpler implementation
- ✅ Better for long-running transactions

### TCC Pattern

**Best for**: Strong consistency requirements, resource reservation, financial transactions

The TCC pattern implements distributed transactions through resource reservation:

```java
@Tcc(name = "OrderPayment")
public class OrderPaymentTcc {

    @TccParticipant(id = "payment", order = 1)
    public static class PaymentParticipant {

        @TryMethod
        public Mono<ReservationId> reservePayment(PaymentRequest request) {
            return paymentService.reserveAmount(request.getAmount());
        }

        @ConfirmMethod
        public Mono<Void> confirmPayment(@FromTry ReservationId id) {
            return paymentService.captureReservation(id);
        }

        @CancelMethod
        public Mono<Void> cancelPayment(@FromTry ReservationId id) {
            return paymentService.releaseReservation(id);
        }
    }
}
```

**Characteristics**:
- ✅ Two-phase commit (Try → Confirm/Cancel)
- ✅ Strong consistency guarantees
- ✅ Resource reservation and locking
- ✅ Automatic rollback on failure
- ✅ TCC-specific annotations (`@FromTry`, `@Header`, `@Input`)

### Execute TCC

```java
@Service
public class OrderService {

    @Autowired
    private TccEngine tccEngine;

    public Mono<OrderResult> processOrder(OrderRequest order) {
        TccInputs inputs = TccInputs.builder()
            .forParticipant("payment", order)
            .build();

        return tccEngine.execute("OrderPayment", inputs)
            .map(result -> {
                if (result.isSuccess()) {
                    return OrderResult.success(result.getTryResult("payment"));
                } else {
                    return OrderResult.failure(result.getError());
                }
            });
    }
}
```

## Persistence and Recovery

### In-Memory Persistence (Default)

Zero-configuration operation with high performance:

```java
@SpringBootApplication
@EnableTransactionalEngine
public class Application {
    // In-memory persistence is enabled by default
    // Perfect for development and testing
}
```

### Redis Persistence

Production-ready persistence with automatic recovery:

```yaml
# application.yml
firefly:
  tx:
    persistence:
      enabled: true
      auto-recovery-enabled: true
      redis:
        host: localhost
        port: 6379
        key-prefix: "firefly:tx:"
```

### Recovery on Startup

```java
@Component
public class RecoveryManager {

    @Autowired
    private SagaRecoveryService recoveryService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        recoveryService.recoverInFlightSagas()
            .subscribe(result ->
                log.info("Recovery completed: {} sagas recovered", 
                    result.getRecovered()));
    }
}
```

## Saga Composition

Orchestrate multiple sagas into coordinated workflows:

```java
@Service
public class OrderFulfillmentService {

    @Autowired
    private SagaCompositor sagaCompositor;

    public Mono<SagaCompositionResult> processOrder(OrderRequest request) {
        SagaComposition composition = SagaCompositor.compose("order-fulfillment")
            .compensationPolicy(CompensationPolicy.GROUPED_PARALLEL)

            // Step 1: Process payment
            .saga("payment-processing")
                .withId("payment")
                .withInput("orderId", request.getOrderId())
                .add()

            // Step 2: Reserve inventory (depends on payment)
            .saga("inventory-reservation")
                .withId("inventory")
                .dependsOn("payment")
                .withDataFrom("payment", "paymentId")
                .add()

            // Step 3: Parallel shipping and notifications
            .saga("shipping-preparation")
                .withId("shipping")
                .dependsOn("inventory")
                .executeInParallelWith("notifications")
                .add()

            .saga("notification-sending")
                .withId("notifications")
                .dependsOn("payment")
                .optional() // Won't fail composition if it fails
                .add()

            .build();

        return sagaCompositor.execute(composition, new SagaContext(request.getCorrelationId()));
    }
}
```

### TCC Compositor

The TCC Compositor allows you to orchestrate multiple TCC transactions with dependencies, parallel execution, and data flow between them.

```java
@Service
public class OrderFulfillmentService {

    @Autowired
    private TccCompositor tccCompositor;

    public Mono<TccCompositionResult> processOrder(OrderRequest request) {
        TccComposition composition = TccCompositor.compose("order-fulfillment")
            .compensationPolicy(CompensationPolicy.GROUPED_PARALLEL)

            // Step 1: Process payment
            .tcc("payment-processing")
                .withId("payment")
                .withInput("amount", request.getAmount())
                .withInput("customerId", request.getCustomerId())
                .add()

            // Step 2: Reserve inventory (depends on payment)
            .tcc("inventory-reservation")
                .withId("inventory")
                .dependsOn("payment")
                .withInput("productId", request.getProductId())
                .withInput("quantity", request.getQuantity())
                .withDataFrom("payment", "payment-participant", "transactionId", "paymentTransactionId")
                .add()

            // Step 3: Arrange shipping (depends on inventory)
            .tcc("shipping-arrangement")
                .withId("shipping")
                .dependsOn("inventory")
                .withInput("address", request.getShippingAddress())
                .withDataFrom("inventory", "inventory-participant", "reservationId", "inventoryReservationId")
                .add()

            // Step 4: Send notification (optional, runs in parallel with shipping)
            .tcc("notification-service")
                .withId("notification")
                .executeInParallelWith("shipping")
                .withInput("customerId", request.getCustomerId())
                .withInput("orderId", request.getOrderId())
                .optional() // Won't fail composition if it fails
                .add()

            .build();

        return tccCompositor.execute(composition, new TccContext(request.getCorrelationId()));
    }
}
```

## Configuration

### Basic Configuration

```yaml
firefly:
  tx:
    saga:
      compensation-policy: STRICT_SEQUENTIAL
      auto-optimization-enabled: true
      default-timeout: PT5M
    tcc:
      default-timeout: PT30S
      auto-recovery-enabled: true
```

### Persistence Configuration

```yaml
firefly:
  tx:
    persistence:
      enabled: true
      auto-recovery-enabled: true
      max-saga-age: PT24H
      cleanup-interval: PT1H
      redis:
        host: localhost
        port: 6379
        database: 0
        key-prefix: "firefly:tx:"
        key-ttl: PT24H
```

## Examples

- [SAGA Composition Example](examples/saga-composition-example/) - Complete order fulfillment workflow
- [TCC E-commerce Example](examples/tcc-ecommerce-order/) - Payment, inventory, and shipping coordination

## Documentation

- [Architecture Guide](docs/ARCHITECTURE.md) - System design and components
- [SAGA Pattern Guide](docs/SAGA_GUIDE.md) - Comprehensive SAGA documentation
- [TCC Pattern Guide](docs/TCC_GUIDE.md) - Complete TCC documentation
- [Configuration Reference](docs/CONFIGURATION.md) - All configuration options
- [API Reference](docs/API_REFERENCE.md) - Complete API documentation

## License

Licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

---

**Firefly Software Solutions Inc** - Building the future of banking technology
