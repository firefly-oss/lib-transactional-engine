# TCC E-commerce Order Example

This example demonstrates a complete e-commerce order processing system using the TCC (Try-Confirm-Cancel) pattern.

## Scenario

When a customer places an order, the system must:
1. **Reserve payment** from the customer's account
2. **Reserve inventory** for the ordered items
3. **Reserve shipping** capacity

If all reservations succeed, the system confirms all operations. If any reservation fails, all previous reservations are canceled.

## Architecture

```
┌─────────────────────────────────────────────────┐
│ OrderService                                    │
│ - Receives order request                        │
│ - Executes TCC transaction                      │
└─────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────┐
│ OrderProcessingTcc                              │
│ - Coordinates 3 participants                    │
└─────────────────────────────────────────────────┘
                    │
        ┌───────────┼───────────┐
        │           │           │
        ▼           ▼           ▼
┌──────────┐  ┌──────────┐  ┌──────────┐
│ Payment  │  │Inventory │  │ Shipping │
│Participant│  │Participant│  │Participant│
└──────────┘  └──────────┘  └──────────┘
```

## Components

### 1. Domain Models

- `OrderRequest`: Customer order details
- `PaymentReservation`: Payment reservation details
- `InventoryReservation`: Inventory reservation details
- `ShippingReservation`: Shipping reservation details

### 2. Services

- `PaymentService`: Manages payment reservations
- `InventoryService`: Manages inventory reservations
- `ShippingService`: Manages shipping reservations

### 3. TCC Transaction

- `OrderProcessingTcc`: Coordinates the three participants
  - `PaymentParticipant`: Try/Confirm/Cancel payment
  - `InventoryParticipant`: Try/Confirm/Cancel inventory
  - `ShippingParticipant`: Try/Confirm/Cancel shipping

### 4. Application Service

- `OrderService`: Main entry point for order processing

## Running the Example

### Prerequisites

- Java 17+
- Maven 3.8+
- Redis (optional, for persistence)

### Build

```bash
mvn clean install
```

### Run

```bash
mvn spring-boot:run
```

### Test

```bash
mvn test
```

## Code Walkthrough

### Step 1: Define the TCC Transaction

```java
@Tcc(name = "OrderProcessing")
public class OrderProcessingTcc {
    
    @TccParticipant(id = "payment", order = 1)
    public static class PaymentParticipant {
        
        @Autowired
        private PaymentService paymentService;
        
        @TryMethod
        public Mono<PaymentReservation> reservePayment(OrderRequest order) {
            return paymentService.reserveAmount(
                order.getCustomerId(),
                order.getTotalAmount()
            );
        }
        
        @ConfirmMethod
        public Mono<Void> confirmPayment(@FromTry PaymentReservation reservation) {
            return paymentService.capturePayment(reservation.getId());
        }
        
        @CancelMethod
        public Mono<Void> cancelPayment(@FromTry PaymentReservation reservation) {
            return paymentService.releasePayment(reservation.getId());
        }
    }
    
    @TccParticipant(id = "inventory", order = 2)
    public static class InventoryParticipant {
        
        @Autowired
        private InventoryService inventoryService;
        
        @TryMethod
        public Mono<InventoryReservation> reserveInventory(OrderRequest order) {
            return inventoryService.reserveItems(order.getItems());
        }
        
        @ConfirmMethod
        public Mono<Void> confirmInventory(@FromTry InventoryReservation reservation) {
            return inventoryService.commitReservation(reservation.getId());
        }
        
        @CancelMethod
        public Mono<Void> cancelInventory(@FromTry InventoryReservation reservation) {
            return inventoryService.releaseReservation(reservation.getId());
        }
    }
    
    @TccParticipant(id = "shipping", order = 3)
    public static class ShippingParticipant {
        
        @Autowired
        private ShippingService shippingService;
        
        @TryMethod
        public Mono<ShippingReservation> reserveShipping(OrderRequest order) {
            return shippingService.reserveCapacity(
                order.getShippingAddress(),
                order.getItems()
            );
        }
        
        @ConfirmMethod
        public Mono<Void> confirmShipping(@FromTry ShippingReservation reservation) {
            return shippingService.scheduleShipment(reservation.getId());
        }
        
        @CancelMethod
        public Mono<Void> cancelShipping(@FromTry ShippingReservation reservation) {
            return shippingService.releaseCapacity(reservation.getId());
        }
    }
}
```

### Step 2: Execute the Transaction

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
        if (result.isSuccess()) {
            PaymentReservation payment = result.getTryResult("payment");
            InventoryReservation inventory = result.getTryResult("inventory");
            ShippingReservation shipping = result.getTryResult("shipping");
            
            return OrderResult.builder()
                .orderId(order.getId())
                .status(OrderStatus.CONFIRMED)
                .paymentId(payment.getId())
                .inventoryReservationId(inventory.getId())
                .shippingId(shipping.getId())
                .build();
        } else {
            return OrderResult.builder()
                .orderId(order.getId())
                .status(OrderStatus.FAILED)
                .failureReason(result.getError().getMessage())
                .failedStep(result.getFailedParticipantId())
                .build();
        }
    }
}
```

## Test Scenarios

### Scenario 1: Successful Order

All participants succeed:
- Payment reserved and confirmed
- Inventory reserved and confirmed
- Shipping reserved and confirmed

**Result**: Order confirmed

### Scenario 2: Insufficient Inventory

- Payment reserved successfully
- Inventory reservation fails (out of stock)
- Shipping not attempted

**Result**: 
- Payment canceled
- Order failed

### Scenario 3: Shipping Unavailable

- Payment reserved successfully
- Inventory reserved successfully
- Shipping reservation fails (no capacity)

**Result**:
- Payment canceled
- Inventory canceled
- Order failed

### Scenario 4: Payment Declined

- Payment reservation fails (insufficient funds)
- Inventory not attempted
- Shipping not attempted

**Result**: Order failed immediately

## Key Learnings

### 1. Resource Reservation

Each service implements a reservation pattern:

```java
public class PaymentService {
    
    // Try: Reserve funds
    public Mono<PaymentReservation> reserveAmount(String customerId, BigDecimal amount) {
        return accountRepository.findByCustomerId(customerId)
            .flatMap(account -> {
                if (account.getBalance().compareTo(amount) < 0) {
                    return Mono.error(new InsufficientFundsException());
                }
                
                PaymentReservation reservation = new PaymentReservation(
                    UUID.randomUUID().toString(),
                    customerId,
                    amount,
                    ReservationStatus.RESERVED
                );
                
                return reservationRepository.save(reservation);
            });
    }
    
    // Confirm: Capture reserved funds
    public Mono<Void> capturePayment(String reservationId) {
        return reservationRepository.findById(reservationId)
            .flatMap(reservation -> {
                reservation.setStatus(ReservationStatus.CAPTURED);
                return reservationRepository.save(reservation)
                    .then(accountRepository.debit(
                        reservation.getCustomerId(),
                        reservation.getAmount()
                    ));
            });
    }
    
    // Cancel: Release reserved funds
    public Mono<Void> releasePayment(String reservationId) {
        return reservationRepository.findById(reservationId)
            .flatMap(reservation -> {
                reservation.setStatus(ReservationStatus.RELEASED);
                return reservationRepository.save(reservation);
            })
            .then();
    }
}
```

### 2. Idempotency

All operations are idempotent:

```java
public Mono<PaymentReservation> reserveAmount(String customerId, BigDecimal amount) {
    String idempotencyKey = generateKey(customerId, amount);
    
    // Check if already reserved
    return reservationRepository.findByIdempotencyKey(idempotencyKey)
        .switchIfEmpty(createNewReservation(customerId, amount, idempotencyKey));
}
```

### 3. Error Handling

Graceful error handling at each level:

```java
@TryMethod
public Mono<PaymentReservation> reservePayment(OrderRequest order) {
    return paymentService.reserveAmount(order.getCustomerId(), order.getTotalAmount())
        .onErrorMap(InsufficientFundsException.class, e ->
            new PaymentReservationException("Customer has insufficient funds", e))
        .onErrorMap(AccountNotFoundException.class, e ->
            new PaymentReservationException("Customer account not found", e));
}
```

### 4. Observability

Add logging and metrics:

```java
@TryMethod
public Mono<PaymentReservation> reservePayment(OrderRequest order) {
    log.info("Reserving payment for order: orderId={}, amount={}", 
        order.getId(), order.getTotalAmount());
    
    return paymentService.reserveAmount(order.getCustomerId(), order.getTotalAmount())
        .doOnSuccess(reservation -> {
            log.info("Payment reserved: reservationId={}", reservation.getId());
            metrics.incrementCounter("payment.reservations.success");
        })
        .doOnError(e -> {
            log.error("Payment reservation failed: orderId={}", order.getId(), e);
            metrics.incrementCounter("payment.reservations.failure");
        });
}
```

## Comparison with SAGA

The same scenario implemented with SAGA would:
- Execute payment immediately (not reserve)
- Execute inventory immediately (not reserve)
- Execute shipping immediately (not reserve)
- Compensate in reverse order if any step fails

**TCC Advantages**:
- Better isolation (resources are locked)
- Stronger consistency guarantees
- No partial state visible to other transactions

**TCC Disadvantages**:
- More complex implementation (reservation logic)
- Requires additional storage (reservation records)
- Longer resource lock times

## Next Steps

- Explore the full source code in this directory
- Run the integration tests
- Try modifying the example to add more participants
- Compare with the SAGA version of the same scenario

