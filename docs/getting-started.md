# Getting Started with Transactional Engine

This comprehensive guide will walk you through setting up and using the Transactional Engine for distributed transaction management in your Spring Boot 3 applications.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Installation](#installation)
3. [Core Concepts](#core-concepts)
4. [Your First Saga](#your-first-saga)
5. [Understanding Compensation](#understanding-compensation)
6. [Step Dependencies](#step-dependencies)
7. [Input and Output Handling](#input-and-output-handling)
8. [Error Handling and Retries](#error-handling-and-retries)
9. [Event Publishing](#event-publishing)
10. [Testing Your Sagas](#testing-your-sagas)
11. [Next Steps](#next-steps)

## Prerequisites

- Java 17 or later
- Spring Boot 3.2.2 or later
- Maven or Gradle
- Basic understanding of reactive programming (Project Reactor)

## Installation

### Maven
Add the core dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.catalis</groupId>
    <artifactId>lib-transactional-engine-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

For AWS integration, also add:
```xml
<dependency>
    <groupId>com.catalis</groupId>
    <artifactId>lib-transactional-engine-aws-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Gradle
```gradle
implementation 'com.catalis:lib-transactional-engine-core:1.0.0-SNAPSHOT'
// For AWS integration
implementation 'com.catalis:lib-transactional-engine-aws-starter:1.0.0-SNAPSHOT'
```

## Core Concepts

### What is a Saga?
A Saga is a sequence of transactions that can be interleaved with other transactions. If one transaction fails, the saga executes compensating transactions to undo the impact of the preceding transactions.

### Key Components

- **Saga**: A workflow consisting of multiple steps
- **Step**: An individual transaction within a saga
- **Compensation**: Reverse operation to undo a step's effects
- **SagaEngine**: The orchestrator that executes sagas
- **StepInputs**: Data passed between steps
- **SagaContext**: Runtime context for saga execution

## Your First Saga

Let's create a simple order processing saga that validates payment, reserves inventory, and creates an order.

### 1. Enable Transactional Engine

First, enable the engine in your main application class:

```java
@SpringBootApplication
@EnableTransactionalEngine
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
```

### 2. Create Domain Objects

```java
// Input data
public class CreateOrderRequest {
    private String customerId;
    private List<OrderItem> items;
    private BigDecimal totalAmount;
    // getters, setters, constructors
}

// Step results
public class PaymentValidationResult {
    private String paymentId;
    private boolean valid;
    private String reason;
    // getters, setters, constructors
}

public class InventoryReservationResult {
    private String reservationId;
    private List<String> reservedItems;
    private Instant expiresAt;
    // getters, setters, constructors
}

public class OrderCreationResult {
    private String orderId;
    private OrderStatus status;
    private Instant createdAt;
    // getters, setters, constructors
}
```

### 3. Define Your Saga

```java
@Component
@Saga(name = "order-processing")
public class OrderProcessingSaga {
    
    private final PaymentService paymentService;
    private final InventoryService inventoryService;
    private final OrderService orderService;
    
    public OrderProcessingSaga(PaymentService paymentService,
                              InventoryService inventoryService,
                              OrderService orderService) {
        this.paymentService = paymentService;
        this.inventoryService = inventoryService;
        this.orderService = orderService;
    }
    
    @SagaStep(id = "validate-payment")
    public Mono<PaymentValidationResult> validatePayment(
            @Input("customerId") String customerId,
            @Input("amount") BigDecimal amount) {
        
        return paymentService.validatePayment(customerId, amount)
            .map(valid -> new PaymentValidationResult(
                UUID.randomUUID().toString(), 
                valid, 
                valid ? "Valid" : "Insufficient funds"));
    }
    
    @SagaStep(id = "reserve-inventory", 
              dependsOn = "validate-payment",
              compensate = "releaseInventory")
    public Mono<InventoryReservationResult> reserveInventory(
            @Input("items") List<OrderItem> items,
            @FromStep("validate-payment") PaymentValidationResult payment) {
        
        if (!payment.isValid()) {
            return Mono.error(new PaymentException("Payment validation failed: " + payment.getReason()));
        }
        
        return inventoryService.reserveItems(items)
            .map(reservation -> new InventoryReservationResult(
                reservation.getId(),
                reservation.getReservedItems(),
                Instant.now().plus(Duration.ofMinutes(15))));
    }
    
    @SagaStep(id = "create-order",
              dependsOn = {"validate-payment", "reserve-inventory"})
    public Mono<OrderCreationResult> createOrder(
            @Input("customerId") String customerId,
            @Input("items") List<OrderItem> items,
            @FromStep("validate-payment") PaymentValidationResult payment,
            @FromStep("reserve-inventory") InventoryReservationResult reservation) {
        
        return orderService.createOrder(customerId, items, payment.getPaymentId(), reservation.getReservationId())
            .map(order -> new OrderCreationResult(
                order.getId(),
                order.getStatus(),
                order.getCreatedAt()));
    }
    
    @CompensationSagaStep
    public Mono<Void> releaseInventory(
            @FromStep("reserve-inventory") InventoryReservationResult reservation) {
        
        return inventoryService.releaseReservation(reservation.getReservationId());
    }
}
```

### 4. Execute the Saga

Create a REST controller to trigger the saga:

```java
@RestController
@RequestMapping("/orders")
public class OrderController {
    
    private final SagaEngine sagaEngine;
    
    public OrderController(SagaEngine sagaEngine) {
        this.sagaEngine = sagaEngine;
    }
    
    @PostMapping
    public Mono<ResponseEntity<SagaResult>> createOrder(
            @RequestBody CreateOrderRequest request) {
        
        StepInputs inputs = StepInputs.builder()
            .input("customerId", request.getCustomerId())
            .input("items", request.getItems())
            .input("amount", request.getTotalAmount())
            .build();
        
        return sagaEngine.execute("order-processing", inputs)
            .map(result -> {
                if (result.isSuccessful()) {
                    return ResponseEntity.ok(result);
                } else {
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
                }
            })
            .onErrorReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }
}
```

## Understanding Compensation

Compensation is the mechanism to undo the effects of completed steps when a saga fails. Let's enhance our example with more robust compensation:

```java
@SagaStep(id = "process-payment",
          dependsOn = "validate-payment",
          compensate = "refundPayment")
public Mono<PaymentProcessResult> processPayment(
        @FromStep("validate-payment") PaymentValidationResult validation) {
    
    return paymentService.processPayment(validation.getPaymentId());
}

@CompensationSagaStep
public Mono<Void> refundPayment(
        @FromStep("process-payment") PaymentProcessResult payment,
        @CompensationError Throwable error) {
    
    // Log why compensation is needed
    log.warn("Refunding payment {} due to saga failure: {}", 
             payment.getPaymentId(), error.getMessage());
    
    return paymentService.refundPayment(payment.getPaymentId());
}
```

## Step Dependencies

Steps can depend on multiple other steps and execute in parallel when possible:

```java
@SagaStep(id = "validate-customer")
public Mono<CustomerValidationResult> validateCustomer(@Input("customerId") String customerId) {
    return customerService.validate(customerId);
}

@SagaStep(id = "check-credit-limit")
public Mono<CreditCheckResult> checkCreditLimit(@Input("customerId") String customerId) {
    return creditService.checkLimit(customerId);
}

// This step waits for both validations to complete
@SagaStep(id = "approve-order",
          dependsOn = {"validate-customer", "check-credit-limit"})
public Mono<ApprovalResult> approveOrder(
        @FromStep("validate-customer") CustomerValidationResult customerResult,
        @FromStep("check-credit-limit") CreditCheckResult creditResult) {
    
    if (!customerResult.isValid()) {
        return Mono.error(new CustomerException("Invalid customer"));
    }
    
    if (!creditResult.isApproved()) {
        return Mono.error(new CreditException("Credit limit exceeded"));
    }
    
    return Mono.just(new ApprovalResult(true, "Order approved"));
}
```

## Input and Output Handling

### Accessing Input Data
```java
@SagaStep(id = "process-order")
public Mono<OrderResult> processOrder(
        @Input("orderId") String orderId,                    // Direct input
        @Input("metadata.priority") String priority,         // Nested property
        @Variable("userId") String userId,                   // Saga variable
        @Headers Map<String, String> headers,               // All headers
        @Header("correlation-id") String correlationId) {   // Specific header
    
    // Implementation
}
```

### Setting Variables
```java
@SagaStep(id = "lookup-customer")
@SetVariable(name = "customerType", fromResult = "type")
@SetVariable(name = "customerTier", fromResult = "tier")
public Mono<CustomerInfo> lookupCustomer(@Input("customerId") String customerId) {
    return customerService.getCustomerInfo(customerId);
}

// Later steps can access these variables
@SagaStep(id = "calculate-discount", dependsOn = "lookup-customer")
public Mono<DiscountResult> calculateDiscount(
        @Variable("customerType") String customerType,
        @Variable("customerTier") String customerTier) {
    
    return discountService.calculateDiscount(customerType, customerTier);
}
```

## Error Handling and Retries

Configure retry policies and error handling:

```java
@SagaStep(id = "external-api-call",
          retry = 3,
          backoffMs = 1000,
          jitter = true,
          jitterFactor = 0.5)
public Mono<ExternalApiResult> callExternalApi(@Input("data") String data) {
    return externalApiClient.call(data)
        .doOnError(error -> log.warn("External API call failed: {}", error.getMessage()));
}

// Handle specific exceptions
@SagaStep(id = "risky-operation")
public Mono<Result> performRiskyOperation(@Input("data") String data) {
    return riskyService.perform(data)
        .onErrorMap(SocketTimeoutException.class, 
                   ex -> new BusinessException("Service temporarily unavailable"))
        .onErrorMap(IllegalArgumentException.class,
                   ex -> new ValidationException("Invalid input: " + ex.getMessage()));
}
```

## Event Publishing

Publish events during saga execution:

```java
@SagaStep(id = "create-order")
@StepEvent(topic = "order-events", type = "ORDER_CREATED")
public Mono<Order> createOrder(@Input("orderData") OrderData data) {
    return orderService.createOrder(data)
        .doOnSuccess(order -> log.info("Order created: {}", order.getId()));
}

@SagaStep(id = "send-notification")
@StepEvent(topic = "notification-events", 
           type = "NOTIFICATION_SENT")
public Mono<NotificationResult> sendNotification(
        @FromStep("create-order") Order order) {
    
    return notificationService.sendOrderConfirmation(order);
}
```

## Testing Your Sagas

### Unit Testing Individual Steps

```java
@ExtendWith(MockitoExtension.class)
class OrderProcessingSagaTest {
    
    @Mock
    private PaymentService paymentService;
    
    @Mock
    private InventoryService inventoryService;
    
    @InjectMocks
    private OrderProcessingSaga saga;
    
    @Test
    void validatePayment_WithValidCustomer_ShouldReturnValidResult() {
        // Given
        String customerId = "customer-123";
        BigDecimal amount = BigDecimal.valueOf(100.00);
        when(paymentService.validatePayment(customerId, amount))
            .thenReturn(Mono.just(true));
        
        // When
        StepVerifier.create(saga.validatePayment(customerId, amount))
            .assertNext(result -> {
                assertThat(result.isValid()).isTrue();
                assertThat(result.getReason()).isEqualTo("Valid");
            })
            .verifyComplete();
    }
}
```

### Integration Testing with SagaEngine

```java
@SpringBootTest
@TestPropertySource(properties = {
    "transactionalengine.step-logging.enabled=true"
})
class OrderProcessingIntegrationTest {
    
    @Autowired
    private SagaEngine sagaEngine;
    
    @MockBean
    private PaymentService paymentService;
    
    @MockBean
    private InventoryService inventoryService;
    
    @MockBean
    private OrderService orderService;
    
    @Test
    void executeOrderProcessingSaga_WithValidInputs_ShouldSucceed() {
        // Given
        when(paymentService.validatePayment(any(), any()))
            .thenReturn(Mono.just(true));
        when(inventoryService.reserveItems(any()))
            .thenReturn(Mono.just(new ReservationInfo("res-123", List.of("item1"))));
        when(orderService.createOrder(any(), any(), any(), any()))
            .thenReturn(Mono.just(new Order("order-123", OrderStatus.CREATED, Instant.now())));
        
        StepInputs inputs = StepInputs.builder()
            .input("customerId", "customer-123")
            .input("items", List.of(new OrderItem("item1", 2)))
            .input("amount", BigDecimal.valueOf(100.00))
            .build();
        
        // When & Then
        StepVerifier.create(sagaEngine.execute("order-processing", inputs))
            .assertNext(result -> {
                assertThat(result.isSuccessful()).isTrue();
                assertThat(result.getCompletedSteps()).hasSize(3);
            })
            .verifyComplete();
    }
}
```

## Next Steps

Now that you've created your first saga, explore these advanced topics:

1. **[Architecture Overview](architecture.md)** - Understand how the engine works internally
2. **[API Reference](api-reference.md)** - Complete reference for all annotations and methods
3. **[Configuration Guide](configuration.md)** - Customize engine behavior and performance
4. **[AWS Integration](aws-integration.md)** - Set up CloudWatch, Kinesis, and SQS integration
5. **[Examples](examples.md)** - More complex real-world scenarios
6. **[Performance Optimization](performance.md)** - Tuning for high-throughput applications

## Common Patterns

### Conditional Steps
```java
@SagaStep(stepId = "conditional-processing")
public Mono<ProcessingResult> conditionalProcessing(
        @Input("processType") String processType,
        @Input("data") Object data) {
    
    return switch (processType) {
        case "PREMIUM" -> premiumProcessingService.process(data);
        case "STANDARD" -> standardProcessingService.process(data);
        default -> Mono.error(new IllegalArgumentException("Unknown process type: " + processType));
    };
}
```

### Dynamic Step Creation
```java
// Use programmatic API for dynamic workflows
public Mono<SagaResult> processOrderWithDynamicSteps(OrderType orderType, StepInputs inputs) {
    return switch (orderType) {
        case DIGITAL -> sagaEngine.execute(DigitalOrderSaga::processDigitalOrder, inputs);
        case PHYSICAL -> sagaEngine.execute(PhysicalOrderSaga::processPhysicalOrder, inputs);
        case SUBSCRIPTION -> sagaEngine.execute(SubscriptionSaga::processSubscription, inputs);
    };
}
```

### Error Aggregation
```java
@SagaStep(stepId = "validate-all")
public Mono<ValidationResult> validateAll(@Input("order") Order order) {
    return Flux.merge(
            validateCustomer(order.getCustomerId()),
            validatePayment(order.getPaymentInfo()),
            validateInventory(order.getItems())
        )
        .collectList()
        .map(ValidationResult::aggregate);
}
```

Happy saga building! 🚀