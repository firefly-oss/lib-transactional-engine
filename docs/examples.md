# Examples and Use Cases

Real-world examples demonstrating various patterns and use cases with the Transactional Engine.

## Table of Contents

1. [Getting Started with In-Memory Starter](#getting-started-with-in-memory-starter)
2. [E-Commerce Order Processing](#e-commerce-order-processing)
3. [Financial Transaction Processing](#financial-transaction-processing)
4. [Travel Booking System](#travel-booking-system)
5. [Microservices Choreography](#microservices-choreography)
6. [Batch Processing Pipeline](#batch-processing-pipeline)
7. [Event-Driven Workflows](#event-driven-workflows)
8. [External API Integration](#external-api-integration)
9. [Long-Running Processes](#long-running-processes)

## Getting Started with In-Memory Starter

Perfect for development, testing, and getting familiar with the Transactional Engine without external dependencies.

### Setup

Add the in-memory starter dependency to your project:

```xml
<dependency>
    <groupId>com.catalis</groupId>
    <artifactId>lib-transactional-engine-inmemory-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Simple Bank Transfer Example

```java
@SpringBootApplication
@EnableTransactionalEngine
public class BankTransferApplication {
    public static void main(String[] args) {
        SpringApplication.run(BankTransferApplication.class, args);
    }
}
```

### Domain Classes

```java
public class TransferRequest {
    private String fromAccount;
    private String toAccount;
    private BigDecimal amount;
    private String reference;
    // constructors, getters, setters...
}

public class DebitResult {
    private String transactionId;
    private BigDecimal newBalance;
    private Instant timestamp;
    // constructors, getters, setters...
}

public class CreditResult {
    private String transactionId;
    private BigDecimal newBalance;
    private Instant timestamp;
    // constructors, getters, setters...
}
```

### Simple Saga

```java
@Component
@Saga("bank-transfer")
public class BankTransferSaga {
    
    private final BankService bankService;
    
    public BankTransferSaga(BankService bankService) {
        this.bankService = bankService;
    }
    
    @SagaStep(id = "debit-source", compensate = "creditSource")
    public Mono<DebitResult> debitSource(@Input("request") TransferRequest request) {
        return bankService.debitAccount(request.getFromAccount(), request.getAmount());
    }
    
    @SagaStep(id = "credit-target", 
              dependsOn = "debit-source", 
              compensate = "debitTarget")
    public Mono<CreditResult> creditTarget(
            @Input("request") TransferRequest request,
            @FromStep("debit-source") DebitResult debitResult) {
        return bankService.creditAccount(request.getToAccount(), request.getAmount());
    }
    
    @CompensationSagaStep
    public Mono<Void> creditSource(@FromStep("debit-source") DebitResult debitResult) {
        return bankService.creditAccount(debitResult.getAccountId(), debitResult.getAmount());
    }
    
    @CompensationSagaStep  
    public Mono<Void> debitTarget(@FromStep("credit-target") CreditResult creditResult) {
        return bankService.debitAccount(creditResult.getAccountId(), creditResult.getAmount());
    }
}
```

### Running the Saga

```java
@RestController
public class TransferController {
    
    private final SagaEngine sagaEngine;
    
    @PostMapping("/transfer")
    public Mono<String> transfer(@RequestBody TransferRequest request) {
        return sagaEngine.execute("bank-transfer", StepInputs.create("request", request))
            .map(result -> "Transfer completed: " + result.getSagaId())
            .onErrorReturn("Transfer failed");
    }
}
```

### Configuration (Optional)

```yaml
# application.yml - Customize logging behavior
transactional-engine:
  inmemory:
    events:
      log-timing: true
      log-compensation: true
      max-events-in-memory: 500
```

### What You'll See

When you run this example, you'll see enhanced logging with emojis:

```
🚀 Saga started: bank-transfer [12345]
▶️  Step started: bank-transfer -> debit-source [12345]
✅ Step succeeded: bank-transfer -> debit-source [12345] (attempts: 1, latency: 245ms)
▶️  Step started: bank-transfer -> credit-target [12345]
✅ Step succeeded: bank-transfer -> credit-target [12345] (attempts: 1, latency: 189ms)
🎉 Saga completed successfully: bank-transfer [12345]
```

The in-memory starter provides:
- 🚀 **Zero configuration** - Works out of the box
- 📝 **Enhanced logging** - Beautiful, readable logs with emojis
- 🔍 **Event history** - Keep recent events in memory for debugging
- ⚡ **Fast startup** - No external dependencies to configure

Perfect for learning, development, and testing!

## E-Commerce Order Processing

Complete e-commerce order processing with inventory, payment, and shipping coordination.

### Domain Model

```java
public class OrderProcessingRequest {
    private String customerId;
    private List<OrderItem> items;
    private PaymentInfo paymentInfo;
    private ShippingAddress shippingAddress;
    private String promotionCode;
}

public class OrderItem {
    private String productId;
    private int quantity;
    private BigDecimal unitPrice;
}

// Step results
public class InventoryReservation {
    private String reservationId;
    private Map<String, Integer> reservedQuantities;
    private Instant expiresAt;
}

public class PaymentResult {
    private String paymentId;
    private String transactionId;
    private PaymentStatus status;
    private BigDecimal amountCharged;
}

public class ShippingLabel {
    private String labelId;
    private String trackingNumber;
    private String carrier;
    private BigDecimal shippingCost;
}
```

### Saga Implementation

```java
@Component
@Saga(name = "ecommerce-order-processing")
public class ECommerceOrderSaga {
    
    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final ShippingService shippingService;
    private final PromotionService promotionService;
    private final NotificationService notificationService;
    
    @SagaStep(id = "validate-customer", timeoutMs = 5000)
    @SetVariable(name = "customerTier", fromResult = "tier")
    @SetVariable(name = "creditLimit", fromResult = "creditLimit")
    public Mono<CustomerInfo> validateCustomer(@Input("customerId") String customerId) {
        return customerService.getCustomerInfo(customerId)
            .switchIfEmpty(Mono.error(new CustomerNotFoundException(customerId)));
    }
    
    @SagaStep(id = "apply-promotions", 
              dependsOn = "validate-customer",
              timeoutMs = 10000)
    @SetVariable(name = "finalAmount", fromResult = "finalAmount")
    @SetVariable(name = "appliedDiscounts", fromResult = "discounts")
    public Mono<PricingResult> applyPromotions(
            @Input("items") List<OrderItem> items,
            @Input("promotionCode") String promotionCode,
            @Variable("customerTier") String customerTier) {
        
        return promotionService.calculatePricing(items, promotionCode, customerTier);
    }
    
    @SagaStep(id = "check-inventory", 
              dependsOn = "apply-promotions",
              compensate = "releaseInventory",
              timeoutMs = 15000,
              retry = 3,
              backoffMs = 2000)
    public Mono<InventoryReservation> checkInventory(
            @Input("items") List<OrderItem> items) {
        
        return inventoryService.reserveItems(items)
            .onErrorMap(InsufficientInventoryException.class, 
                       ex -> new SagaStepException("Insufficient inventory: " + ex.getMessage()));
    }
    
    @SagaStep(id = "validate-payment", 
              dependsOn = {"validate-customer", "apply-promotions"},
              timeoutMs = 30000)
    public Mono<PaymentValidation> validatePayment(
            @Input("paymentInfo") PaymentInfo paymentInfo,
            @Variable("finalAmount") BigDecimal amount,
            @Variable("creditLimit") BigDecimal creditLimit) {
        
        if (amount.compareTo(creditLimit) > 0) {
            return Mono.error(new CreditLimitExceededException(amount, creditLimit));
        }
        
        return paymentService.validatePayment(paymentInfo, amount);
    }
    
    @SagaStep(id = "process-payment", 
              dependsOn = {"validate-payment", "check-inventory"},
              compensate = "refundPayment",
              timeoutMs = 45000,
              retry = 2,
              backoffMs = 5000)
    @StepEvent(eventType = "PAYMENT_PROCESSED", topic = "payment-events", includeResult = true)
    public Mono<PaymentResult> processPayment(
            @FromStep("validate-payment") PaymentValidation validation,
            @Variable("finalAmount") BigDecimal amount) {
        
        return paymentService.processPayment(validation.getPaymentMethod(), amount);
    }
    
    @SagaStep(id = "create-shipping-label", 
              dependsOn = "process-payment",
              compensate = "cancelShipping",
              timeoutMs = 20000)
    @StepEvent(eventType = "SHIPPING_LABEL_CREATED", topic = "shipping-events")
    public Mono<ShippingLabel> createShippingLabel(
            @Input("shippingAddress") ShippingAddress address,
            @Input("items") List<OrderItem> items,
            @Variable("customerTier") String customerTier) {
        
        ShippingMethod method = determineShippingMethod(customerTier);
        return shippingService.createLabel(address, items, method);
    }
    
    @SagaStep(id = "create-order", 
              dependsOn = {"process-payment", "create-shipping-label"},
              timeoutMs = 10000)
    @StepEvent(eventType = "ORDER_CREATED", topic = "order-events", includeResult = true)
    public Mono<Order> createOrder(
            @Input("customerId") String customerId,
            @Input("items") List<OrderItem> items,
            @FromStep("check-inventory") InventoryReservation inventory,
            @FromStep("process-payment") PaymentResult payment,
            @FromStep("create-shipping-label") ShippingLabel shipping,
            @Variable("appliedDiscounts") List<Discount> discounts) {
        
        return orderService.createOrder(Order.builder()
            .customerId(customerId)
            .items(items)
            .inventoryReservationId(inventory.getReservationId())
            .paymentId(payment.getPaymentId())
            .shippingLabelId(shipping.getLabelId())
            .appliedDiscounts(discounts)
            .build());
    }
    
    @SagaStep(id = "send-confirmation", 
              dependsOn = "create-order",
              timeoutMs = 5000)
    public Mono<Void> sendConfirmation(
            @FromStep("create-order") Order order,
            @Variable("customerTier") String customerTier) {
        
        return notificationService.sendOrderConfirmation(order, customerTier);
    }
    
    // Compensation methods
    @CompensationSagaStep
    public Mono<Void> releaseInventory(
            @FromStep("check-inventory") InventoryReservation reservation) {
        return inventoryService.releaseReservation(reservation.getReservationId());
    }
    
    @CompensationSagaStep
    public Mono<Void> refundPayment(
            @FromStep("process-payment") PaymentResult payment) {
        return paymentService.refundPayment(payment.getPaymentId());
    }
    
    @CompensationSagaStep(timeout = "20s")
    public Mono<Void> cancelShipping(
            @FromStep("create-shipping-label") ShippingLabel label) {
        return shippingService.cancelLabel(label.getLabelId());
    }
}
```

### Usage

```java
@RestController
@RequestMapping("/orders")
public class OrderController {
    
    private final SagaEngine sagaEngine;
    
    @PostMapping
    public Mono<ResponseEntity<OrderResponse>> createOrder(
            @RequestBody OrderProcessingRequest request) {
        
        StepInputs inputs = StepInputs.builder()
            .input("customerId", request.getCustomerId())
            .input("items", request.getItems())
            .input("paymentInfo", request.getPaymentInfo())
            .input("shippingAddress", request.getShippingAddress())
            .input("promotionCode", request.getPromotionCode())
            .header("correlation-id", UUID.randomUUID().toString())
            .build();
        
        return sagaEngine.execute("ecommerce-order-processing", inputs)
            .map(result -> {
                if (result.isSuccessful()) {
                    Order order = result.getStepResult("create-order", Order.class);
                    return ResponseEntity.ok(OrderResponse.fromOrder(order));
                } else {
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(OrderResponse.error(result.getLastError()));
                }
            });
    }
}
```

## Financial Transaction Processing

High-security financial transaction with fraud detection, compliance checks, and settlement.

```java
@Component
@Saga(name = "financial-transaction")
public class FinancialTransactionSaga {
    
    @SagaStep(id = "fraud-check", timeoutMs = 10000)
    @SetVariable(name = "riskScore", fromResult = "riskScore")
    public Mono<FraudCheckResult> performFraudCheck(
            @Input("transaction") TransactionRequest transaction) {
        
        return fraudDetectionService.analyzeTransaction(transaction);
    }
    
    @SagaStep(id = "compliance-check", 
              dependsOn = "fraud-check",
              timeoutMs = 15000)
    public Mono<ComplianceResult> checkCompliance(
            @Input("transaction") TransactionRequest transaction,
            @Variable("riskScore") Double riskScore) {
        
        if (riskScore > 0.8) {
            return complianceService.performEnhancedDueDiligence(transaction);
        }
        return complianceService.performStandardCheck(transaction);
    }
    
    @SagaStep(id = "reserve-funds", 
              dependsOn = {"fraud-check", "compliance-check"},
              compensate = "releaseFunds",
              timeoutMs = 30000)
    public Mono<FundsReservation> reserveFunds(
            @Input("transaction") TransactionRequest transaction,
            @FromStep("fraud-check") FraudCheckResult fraudResult,
            @FromStep("compliance-check") ComplianceResult complianceResult) {
        
        if (!fraudResult.isApproved() || !complianceResult.isApproved()) {
            return Mono.error(new TransactionRejectedException("Transaction rejected"));
        }
        
        return accountService.reserveFunds(
            transaction.getFromAccount(), 
            transaction.getAmount());
    }
    
    @SagaStep(id = "execute-transfer", 
              dependsOn = "reserve-funds",
              compensate = "reverseTransfer",
              timeoutMs = 45000)
    @StepEvent(eventType = "TRANSFER_EXECUTED", topic = "financial-events")
    public Mono<TransferResult> executeTransfer(
            @Input("transaction") TransactionRequest transaction,
            @FromStep("reserve-funds") FundsReservation reservation) {
        
        return transferService.executeTransfer(
            transaction.getFromAccount(),
            transaction.getToAccount(),
            transaction.getAmount(),
            reservation.getReservationId());
    }
    
    @SagaStep(id = "update-balances", 
              dependsOn = "execute-transfer",
              compensate = "revertBalances",
              timeoutMs = 20000)
    public Mono<BalanceUpdate> updateBalances(
            @FromStep("execute-transfer") TransferResult transfer) {
        
        return accountService.updateBalances(transfer);
    }
    
    @SagaStep(id = "create-audit-trail", 
              dependsOn = "update-balances",
              timeoutMs = 10000)
    public Mono<AuditRecord> createAuditTrail(
            @Input("transaction") TransactionRequest transaction,
            @FromStep("execute-transfer") TransferResult transfer,
            @Variable("riskScore") Double riskScore) {
        
        return auditService.createRecord(transaction, transfer, riskScore);
    }
    
    // Compensation methods
    @CompensationSagaStep
    public Mono<Void> releaseFunds(@FromStep("reserve-funds") FundsReservation reservation) {
        return accountService.releaseFunds(reservation);
    }
    
    @CompensationSagaStep
    public Mono<Void> reverseTransfer(@FromStep("execute-transfer") TransferResult transfer) {
        return transferService.reverseTransfer(transfer.getTransactionId());
    }
    
    @CompensationSagaStep
    public Mono<Void> revertBalances(@FromStep("update-balances") BalanceUpdate update) {
        return accountService.revertBalanceUpdate(update);
    }
}
```

## Travel Booking System

Multi-service travel booking with flights, hotels, and car rentals.

```java
@Component
@Saga(name = "travel-booking")
public class TravelBookingSaga {
    
    @SagaStep(id = "validate-traveler", timeoutMs = 5000)
    public Mono<TravelerInfo> validateTraveler(@Input("travelerId") String travelerId) {
        return travelerService.getTravelerInfo(travelerId);
    }
    
    // These steps can run in parallel - no dependencies between them
    @SagaStep(id = "book-flight", 
              dependsOn = "validate-traveler",
              compensate = "cancelFlight",
              timeoutMs = 60000)
    @StepEvent(eventType = "FLIGHT_BOOKED", topic = "travel-events")
    public Mono<FlightBooking> bookFlight(
            @Input("flightDetails") FlightRequest flightDetails,
            @FromStep("validate-traveler") TravelerInfo traveler) {
        
        return flightService.bookFlight(flightDetails, traveler);
    }
    
    @SagaStep(id = "book-hotel", 
              dependsOn = "validate-traveler",
              compensate = "cancelHotel",
              timeoutMs = 45000)
    @StepEvent(eventType = "HOTEL_BOOKED", topic = "travel-events")
    public Mono<HotelBooking> bookHotel(
            @Input("hotelDetails") HotelRequest hotelDetails,
            @FromStep("validate-traveler") TravelerInfo traveler) {
        
        return hotelService.bookRoom(hotelDetails, traveler);
    }
    
    @SagaStep(id = "book-car-rental", 
              dependsOn = "validate-traveler",
              compensate = "cancelCarRental",
              timeoutMs = 30000)
    @StepEvent(eventType = "CAR_BOOKED", topic = "travel-events")
    public Mono<CarRentalBooking> bookCarRental(
            @Input("carDetails") CarRentalRequest carDetails,
            @FromStep("validate-traveler") TravelerInfo traveler) {
        
        return carRentalService.bookCar(carDetails, traveler);
    }
    
    @SagaStep(id = "calculate-total", 
              dependsOn = {"book-flight", "book-hotel", "book-car-rental"},
              timeoutMs = 5000)
    @SetVariable(name = "totalAmount", fromResult = "totalAmount")
    public Mono<PriceSummary> calculateTotal(
            @FromStep("book-flight") FlightBooking flight,
            @FromStep("book-hotel") HotelBooking hotel,
            @FromStep("book-car-rental") CarRentalBooking carRental) {
        
        BigDecimal total = flight.getPrice()
            .add(hotel.getPrice())
            .add(carRental.getPrice());
            
        return Mono.just(new PriceSummary(total, 
            List.of(flight.getPrice(), hotel.getPrice(), carRental.getPrice())));
    }
    
    @SagaStep(id = "process-payment", 
              dependsOn = "calculate-total",
              compensate = "refundPayment",
              timeoutMs = 60000)
    public Mono<PaymentResult> processPayment(
            @Input("paymentInfo") PaymentInfo paymentInfo,
            @Variable("totalAmount") BigDecimal amount) {
        
        return paymentService.processPayment(paymentInfo, amount);
    }
    
    @SagaStep(id = "create-itinerary", 
              dependsOn = "process-payment",
              timeoutMs = 10000)
    public Mono<TravelItinerary> createItinerary(
            @Input("travelerId") String travelerId,
            @FromStep("book-flight") FlightBooking flight,
            @FromStep("book-hotel") HotelBooking hotel,
            @FromStep("book-car-rental") CarRentalBooking carRental,
            @FromStep("process-payment") PaymentResult payment) {
        
        return itineraryService.createItinerary(
            travelerId, flight, hotel, carRental, payment);
    }
    
    @SagaStep(id = "send-confirmation", 
              dependsOn = "create-itinerary",
              timeoutMs = 5000)
    public Mono<Void> sendConfirmation(
            @FromStep("validate-traveler") TravelerInfo traveler,
            @FromStep("create-itinerary") TravelItinerary itinerary) {
        
        return notificationService.sendTravelConfirmation(traveler, itinerary);
    }
    
    // Compensation methods
    @CompensationSagaStep
    public Mono<Void> cancelFlight(@FromStep("book-flight") FlightBooking booking) {
        return flightService.cancelBooking(booking.getConfirmationCode());
    }
    
    @CompensationSagaStep  
    public Mono<Void> cancelHotel(@FromStep("book-hotel") HotelBooking booking) {
        return hotelService.cancelBooking(booking.getConfirmationCode());
    }
    
    @CompensationSagaStep
    public Mono<Void> cancelCarRental(@FromStep("book-car-rental") CarRentalBooking booking) {
        return carRentalService.cancelBooking(booking.getConfirmationCode());
    }
    
    @CompensationSagaStep
    public Mono<Void> refundPayment(@FromStep("process-payment") PaymentResult payment) {
        return paymentService.refundPayment(payment.getPaymentId());
    }
}
```

## Microservices Choreography

Coordinating multiple microservices in an event-driven architecture.

```java
@Component
@Saga(name = "microservices-workflow")
public class MicroservicesChoreographySaga {
    
    @SagaStep(id = "user-service-call")
    @StepEvent(eventType = "USER_VALIDATED", topic = "user-events")
    public Mono<UserInfo> callUserService(@Input("userId") String userId) {
        return userServiceClient.getUser(userId);
    }
    
    @SagaStep(id = "inventory-service-call", 
              dependsOn = "user-service-call")
    @StepEvent(eventType = "INVENTORY_CHECKED", topic = "inventory-events")
    public Mono<InventoryInfo> callInventoryService(
            @Input("productId") String productId,
            @Input("quantity") Integer quantity) {
        
        return inventoryServiceClient.checkAvailability(productId, quantity);
    }
    
    @SagaStep(id = "pricing-service-call", 
              dependsOn = "user-service-call")
    @StepEvent(eventType = "PRICE_CALCULATED", topic = "pricing-events")
    public Mono<PricingInfo> callPricingService(
            @Input("productId") String productId,
            @FromStep("user-service-call") UserInfo user) {
        
        return pricingServiceClient.calculatePrice(productId, user.getCustomerTier());
    }
    
    @SagaStep(id = "recommendation-service-call", 
              dependsOn = "user-service-call")
    @StepEvent(eventType = "RECOMMENDATIONS_GENERATED", topic = "recommendation-events")
    public Mono<RecommendationInfo> callRecommendationService(
            @FromStep("user-service-call") UserInfo user) {
        
        return recommendationServiceClient.getRecommendations(user.getUserId());
    }
    
    @SagaStep(id = "aggregate-results", 
              dependsOn = {"inventory-service-call", "pricing-service-call", "recommendation-service-call"})
    @StepEvent(eventType = "RESULTS_AGGREGATED", topic = "aggregation-events", includeResult = true)
    public Mono<AggregatedResult> aggregateResults(
            @FromStep("user-service-call") UserInfo user,
            @FromStep("inventory-service-call") InventoryInfo inventory,
            @FromStep("pricing-service-call") PricingInfo pricing,
            @FromStep("recommendation-service-call") RecommendationInfo recommendations) {
        
        return Mono.just(AggregatedResult.builder()
            .user(user)
            .inventory(inventory)
            .pricing(pricing)
            .recommendations(recommendations)
            .timestamp(Instant.now())
            .build());
    }
}
```

## Batch Processing Pipeline

Long-running batch processing with checkpoints and recovery.

```java
@Component
@Saga(name = "batch-processing-pipeline")
public class BatchProcessingSaga {
    
    @SagaStep(id = "validate-input-file", timeoutMs = 30000)
    @SetVariable(name = "recordCount", fromResult = "recordCount")
    public Mono<FileValidationResult> validateInputFile(@Input("filePath") String filePath) {
        return fileValidationService.validateFile(filePath);
    }
    
    @SagaStep(id = "extract-data", 
              dependsOn = "validate-input-file",
              timeoutMs = 300000) // 5 minutes
    @SetVariable(name = "extractedRecords", fromResult = "records")
    public Mono<ExtractionResult> extractData(
            @Input("filePath") String filePath,
            @Variable("recordCount") Long recordCount) {
        
        return dataExtractionService.extractRecords(filePath, recordCount);
    }
    
    @SagaStep(id = "transform-data", 
              dependsOn = "extract-data",
              timeoutMs = 600000) // 10 minutes
    @SetVariable(name = "transformedRecords", fromResult = "records")
    public Mono<TransformationResult> transformData(
            @Variable("extractedRecords") List<RawRecord> records) {
        
        return dataTransformationService.transformRecords(records);
    }
    
    @SagaStep(id = "validate-data", 
              dependsOn = "transform-data",
              timeoutMs = 300000)
    @SetVariable(name = "validRecords", fromResult = "validRecords")
    @SetVariable(name = "invalidRecords", fromResult = "invalidRecords")
    public Mono<ValidationResult> validateData(
            @Variable("transformedRecords") List<TransformedRecord> records) {
        
        return dataValidationService.validateRecords(records);
    }
    
    @SagaStep(id = "load-data", 
              dependsOn = "validate-data",
              compensate = "rollbackLoad",
              timeoutMs = 900000) // 15 minutes
    public Mono<LoadResult> loadData(
            @Variable("validRecords") List<ValidRecord> records) {
        
        return dataLoadService.loadRecords(records);
    }
    
    @SagaStep(id = "generate-report", 
              dependsOn = "load-data",
              timeoutMs = 60000)
    public Mono<ProcessingReport> generateReport(
            @Variable("recordCount") Long totalRecords,
            @Variable("validRecords") List<ValidRecord> validRecords,
            @Variable("invalidRecords") List<InvalidRecord> invalidRecords,
            @FromStep("load-data") LoadResult loadResult) {
        
        return reportGenerationService.generateProcessingReport(
            totalRecords, validRecords.size(), invalidRecords.size(), loadResult);
    }
    
    @SagaStep(id = "send-notification", 
              dependsOn = "generate-report",
              timeoutMs = 30000)
    public Mono<Void> sendNotification(
            @FromStep("generate-report") ProcessingReport report) {
        
        return notificationService.sendBatchProcessingReport(report);
    }
    
    @CompensationSagaStep
    public Mono<Void> rollbackLoad(@FromStep("load-data") LoadResult loadResult) {
        return dataLoadService.rollbackLoad(loadResult.getBatchId());
    }
}
```

## Testing Examples

### Unit Testing Individual Steps

```java
@ExtendWith(MockitoExtension.class)
class ECommerceOrderSagaTest {
    
    @Mock private InventoryService inventoryService;
    @Mock private PaymentService paymentService;
    @Mock private CustomerService customerService;
    
    @InjectMocks private ECommerceOrderSaga saga;
    
    @Test
    void validateCustomer_WithValidCustomer_ShouldReturnCustomerInfo() {
        // Given
        String customerId = "customer-123";
        CustomerInfo expectedInfo = CustomerInfo.builder()
            .customerId(customerId)
            .tier("PREMIUM")
            .creditLimit(BigDecimal.valueOf(10000))
            .build();
            
        when(customerService.getCustomerInfo(customerId))
            .thenReturn(Mono.just(expectedInfo));
        
        // When & Then
        StepVerifier.create(saga.validateCustomer(customerId))
            .expectNext(expectedInfo)
            .verifyComplete();
    }
    
    @Test
    void checkInventory_WithInsufficientInventory_ShouldThrowException() {
        // Given
        List<OrderItem> items = List.of(new OrderItem("product-1", 10, BigDecimal.TEN));
        when(inventoryService.reserveItems(items))
            .thenReturn(Mono.error(new InsufficientInventoryException("Not enough stock")));
        
        // When & Then
        StepVerifier.create(saga.checkInventory(items))
            .expectErrorMatches(throwable -> 
                throwable instanceof SagaStepException &&
                throwable.getMessage().contains("Insufficient inventory"))
            .verify();
    }
}
```

### Integration Testing

```java
@SpringBootTest
@TestPropertySource(properties = {
    "transactional-engine.compensation-policy=COMPENSATE_ALL",
    "spring.profiles.active=test"
})
class ECommerceOrderIntegrationTest {
    
    @Autowired private SagaEngine sagaEngine;
    @MockBean private InventoryService inventoryService;
    @MockBean private PaymentService paymentService;
    
    @Test
    void executeOrderSaga_WithValidInputs_ShouldCompleteSuccessfully() {
        // Given
        setupMocks();
        
        StepInputs inputs = StepInputs.builder()
            .input("customerId", "customer-123")
            .input("items", createOrderItems())
            .input("paymentInfo", createPaymentInfo())
            .input("shippingAddress", createShippingAddress())
            .build();
        
        // When & Then
        StepVerifier.create(sagaEngine.execute("ecommerce-order-processing", inputs))
            .assertNext(result -> {
                assertThat(result.isSuccessful()).isTrue();
                assertThat(result.getCompletedSteps()).contains(
                    "validate-customer", "check-inventory", 
                    "process-payment", "create-order");
                
                Order order = result.getStepResult("create-order", Order.class);
                assertThat(order).isNotNull();
                assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
            })
            .verifyComplete();
    }
    
    @Test
    void executeOrderSaga_WithPaymentFailure_ShouldCompensate() {
        // Given
        setupMocksWithPaymentFailure();
        
        StepInputs inputs = createValidInputs();
        
        // When & Then
        StepVerifier.create(sagaEngine.execute("ecommerce-order-processing", inputs))
            .assertNext(result -> {
                assertThat(result.isSuccessful()).isFalse();
                assertThat(result.isCompensated()).isTrue();
                assertThat(result.getCompensatedSteps()).contains("check-inventory");
            })
            .verifyComplete();
            
        // Verify compensation was called
        verify(inventoryService).releaseReservation(any());
    }
    
    private void setupMocks() {
        when(customerService.getCustomerInfo(any()))
            .thenReturn(Mono.just(createCustomerInfo()));
        when(inventoryService.reserveItems(any()))
            .thenReturn(Mono.just(createInventoryReservation()));
        when(paymentService.processPayment(any(), any()))
            .thenReturn(Mono.just(createPaymentResult()));
        // ... other mocks
    }
}
```

These examples demonstrate various patterns and use cases, from simple linear workflows to complex parallel processing with comprehensive error handling and compensation strategies.