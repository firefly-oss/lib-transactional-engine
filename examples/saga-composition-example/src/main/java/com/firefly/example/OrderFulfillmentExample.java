/*
 * Copyright 2025 Firefly Software Solutions Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.firefly.example;

import com.firefly.transactional.annotations.EnableTransactionalEngine;
import com.firefly.transactional.annotations.Saga;
import com.firefly.transactional.annotations.SagaStep;
import com.firefly.transactional.annotations.Input;
import com.firefly.transactional.annotations.FromStep;
import com.firefly.transactional.composition.engine.SagaComposition;
import com.firefly.transactional.composition.engine.SagaCompositionResult;
import com.firefly.transactional.composition.engine.SagaCompositor;
import com.firefly.transactional.core.SagaContext;
import com.firefly.transactional.engine.CompensationPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Example application demonstrating Saga Composition for order fulfillment.
 * 
 * This example shows how to orchestrate multiple sagas into a coordinated
 * workflow for processing e-commerce orders.
 */
@SpringBootApplication
@EnableTransactionalEngine
public class OrderFulfillmentExample {

    public static void main(String[] args) {
        SpringApplication.run(OrderFulfillmentExample.class, args);
    }

    @RestController
    @RequestMapping("/api/orders")
    public static class OrderController {

        @Autowired
        private OrderFulfillmentService orderService;

        @PostMapping
        public Mono<OrderFulfillmentResponse> processOrder(@RequestBody OrderRequest request) {
            return orderService.processOrder(request);
        }
    }

    @Service
    public static class OrderFulfillmentService {

        @Autowired
        private SagaCompositor sagaCompositor;

        public Mono<OrderFulfillmentResponse> processOrder(OrderRequest request) {
            // Create a comprehensive order fulfillment composition
            SagaComposition composition = SagaCompositor.compose("order-fulfillment-workflow")
                    .compensationPolicy(CompensationPolicy.GROUPED_PARALLEL)

                    // Step 1: Validate and process payment
                    .saga("payment-processing")
                        .withId("payment")
                        .withInput("orderId", request.getOrderId())
                        .withInput("customerId", request.getCustomerId())
                        .withInput("amount", request.getAmount())
                        .withInput("paymentMethod", request.getPaymentMethod())
                        .add()

                    // Step 2: Reserve inventory (depends on successful payment)
                    .saga("inventory-management")
                        .withId("inventory")
                        .dependsOn("payment")
                        .withDataFrom("payment", "paymentId")
                        .withInput("orderId", request.getOrderId())
                        .withInput("items", request.getItems())
                        .add()

                    // Step 3: Calculate shipping (can run in parallel with notifications)
                    .saga("shipping-management")
                        .withId("shipping")
                        .dependsOn("inventory")
                        .executeInParallelWith("notifications")
                        .withDataFrom("inventory", "reservationId")
                        .withInput("orderId", request.getOrderId())
                        .withInput("shippingAddress", request.getShippingAddress())
                        .add()

                    // Step 4: Send notifications (runs in parallel with shipping)
                    .saga("notification-service")
                        .withId("notifications")
                        .dependsOn("payment")
                        .withDataFrom("payment", "paymentId")
                        .withDataFrom("payment", "amount")
                        .withInput("customerId", request.getCustomerId())
                        .withInput("orderId", request.getOrderId())
                        .optional() // Don't fail the entire order if notifications fail
                        .timeout(10000) // 10 second timeout
                        .add()

                    // Step 5: Update loyalty points (optional, depends on payment)
                    .saga("loyalty-service")
                        .withId("loyalty")
                        .dependsOn("payment")
                        .executeIf(ctx -> {
                            // Only award points for orders over $50
                            Double amount = (Double) ctx.getSagaResultValue("payment", "amount");
                            return amount != null && amount >= 50.0;
                        })
                        .withDataFrom("payment", "amount", "purchaseAmount")
                        .withInput("customerId", request.getCustomerId())
                        .optional()
                        .add()

                    .build();

            SagaContext context = new SagaContext(UUID.randomUUID().toString());
            context.putHeader("X-Customer-Id", request.getCustomerId());
            context.putHeader("X-Order-Id", request.getOrderId());

            return sagaCompositor.execute(composition, context)
                    .map(this::buildResponse);
        }

        private OrderFulfillmentResponse buildResponse(SagaCompositionResult result) {
            OrderFulfillmentResponse response = new OrderFulfillmentResponse();
            response.setSuccess(result.isSuccess());
            response.setCompositionId(result.getCompositionId());
            response.setProcessingTimeMs(result.getDuration().toMillis());
            response.setCompletedSagas(result.getCompletedSagaCount());
            response.setFailedSagas(result.getFailedSagaCount());

            if (result.isSuccess()) {
                // Extract key results from the composition
                response.setPaymentId((String) result.getSharedVariables().get("paymentId"));
                response.setReservationId((String) result.getSharedVariables().get("reservationId"));
                response.setShipmentId((String) result.getSharedVariables().get("shipmentId"));
                response.setEstimatedDelivery((String) result.getSharedVariables().get("estimatedDelivery"));
                
                Integer loyaltyPoints = (Integer) result.getSharedVariables().get("loyaltyPointsAwarded");
                response.setLoyaltyPointsAwarded(loyaltyPoints != null ? loyaltyPoints : 0);
            } else {
                response.setErrorMessage("Order processing failed. Please try again.");
                response.setFailedSagaIds(result.getFailedSagas());
            }

            return response;
        }
    }

    // Individual Saga Implementations

    @Component
    @Saga(name = "payment-processing")
    public static class PaymentProcessingSaga {

        @SagaStep(id = "validate-payment")
        public Mono<PaymentValidation> validatePayment(@Input("customerId") String customerId,
                                                      @Input("amount") Double amount,
                                                      @Input("paymentMethod") String paymentMethod) {
            // Simulate payment validation
            return Mono.delay(java.time.Duration.ofMillis(100))
                    .map(tick -> new PaymentValidation(customerId, amount, paymentMethod, true));
        }

        @SagaStep(id = "process-payment", dependsOn = "validate-payment", compensate = "refundPayment")
        public Mono<PaymentResult> processPayment(@FromStep("validate-payment") PaymentValidation validation,
                                                 @Input("orderId") String orderId,
                                                 SagaContext context) {
            // Simulate payment processing
            String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8);
            
            // Store payment ID for other sagas to use
            context.putVariable("shared.paymentId", paymentId);
            context.putVariable("shared.amount", validation.amount());
            
            return Mono.delay(java.time.Duration.ofMillis(200))
                    .map(tick -> new PaymentResult(paymentId, orderId, validation.amount(), "COMPLETED"));
        }

        public Mono<Void> refundPayment(@FromStep("process-payment") PaymentResult payment) {
            // Simulate refund processing
            return Mono.delay(java.time.Duration.ofMillis(100))
                    .doOnNext(tick -> System.out.println("Refunded payment: " + payment.paymentId()))
                    .then();
        }
    }

    @Component
    @Saga(name = "inventory-management")
    public static class InventoryManagementSaga {

        @SagaStep(id = "check-availability")
        public Mono<InventoryCheck> checkAvailability(@Input("items") java.util.List<OrderItem> items) {
            // Simulate inventory check
            return Mono.delay(java.time.Duration.ofMillis(150))
                    .map(tick -> new InventoryCheck(items, true));
        }

        @SagaStep(id = "reserve-inventory", dependsOn = "check-availability", compensate = "releaseInventory")
        public Mono<InventoryReservation> reserveInventory(@FromStep("check-availability") InventoryCheck check,
                                                          @Input("orderId") String orderId,
                                                          SagaContext context) {
            String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8);
            
            // Store reservation ID for shipping saga
            context.putVariable("shared.reservationId", reservationId);
            
            return Mono.delay(java.time.Duration.ofMillis(100))
                    .map(tick -> new InventoryReservation(reservationId, orderId, check.items()));
        }

        public Mono<Void> releaseInventory(@FromStep("reserve-inventory") InventoryReservation reservation) {
            return Mono.delay(java.time.Duration.ofMillis(50))
                    .doOnNext(tick -> System.out.println("Released inventory reservation: " + reservation.reservationId()))
                    .then();
        }
    }

    @Component
    @Saga(name = "shipping-management")
    public static class ShippingManagementSaga {

        @SagaStep(id = "calculate-shipping")
        public Mono<ShippingCalculation> calculateShipping(@Input("shippingAddress") String address) {
            return Mono.delay(java.time.Duration.ofMillis(100))
                    .map(tick -> new ShippingCalculation(address, 9.99, "STANDARD"));
        }

        @SagaStep(id = "create-shipment", dependsOn = "calculate-shipping")
        public Mono<ShipmentCreation> createShipment(@FromStep("calculate-shipping") ShippingCalculation calc,
                                                    @Input("orderId") String orderId,
                                                    SagaContext context) {
            String shipmentId = "SHIP-" + UUID.randomUUID().toString().substring(0, 8);
            String estimatedDelivery = LocalDateTime.now().plusDays(3).toString();
            
            context.putVariable("shared.shipmentId", shipmentId);
            context.putVariable("shared.estimatedDelivery", estimatedDelivery);
            
            return Mono.delay(java.time.Duration.ofMillis(150))
                    .map(tick -> new ShipmentCreation(shipmentId, orderId, estimatedDelivery));
        }
    }

    @Component
    @Saga(name = "notification-service")
    public static class NotificationServiceSaga {

        @SagaStep(id = "send-order-confirmation")
        public Mono<NotificationResult> sendOrderConfirmation(@Input("customerId") String customerId,
                                                             @Input("orderId") String orderId) {
            return Mono.delay(java.time.Duration.ofMillis(50))
                    .map(tick -> new NotificationResult("ORDER_CONFIRMATION", customerId, orderId, true));
        }
    }

    @Component
    @Saga(name = "loyalty-service")
    public static class LoyaltyServiceSaga {

        @SagaStep(id = "award-points")
        public Mono<LoyaltyResult> awardPoints(@Input("customerId") String customerId,
                                              @Input("purchaseAmount") Double amount,
                                              SagaContext context) {
            // Award 1 point per dollar spent
            int points = amount.intValue();
            
            context.putVariable("shared.loyaltyPointsAwarded", points);
            
            return Mono.delay(java.time.Duration.ofMillis(75))
                    .map(tick -> new LoyaltyResult(customerId, points));
        }
    }

    // Data Transfer Objects
    public record OrderRequest(String orderId, String customerId, Double amount, 
                              String paymentMethod, java.util.List<OrderItem> items, 
                              String shippingAddress) {}
    
    public record OrderItem(String productId, String name, int quantity, Double price) {}
    
    public static class OrderFulfillmentResponse {
        private boolean success;
        private String compositionId;
        private long processingTimeMs;
        private int completedSagas;
        private int failedSagas;
        private String paymentId;
        private String reservationId;
        private String shipmentId;
        private String estimatedDelivery;
        private int loyaltyPointsAwarded;
        private String errorMessage;
        private java.util.Set<String> failedSagaIds;
        
        // Getters and setters
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getCompositionId() { return compositionId; }
        public void setCompositionId(String compositionId) { this.compositionId = compositionId; }
        public long getProcessingTimeMs() { return processingTimeMs; }
        public void setProcessingTimeMs(long processingTimeMs) { this.processingTimeMs = processingTimeMs; }
        public int getCompletedSagas() { return completedSagas; }
        public void setCompletedSagas(int completedSagas) { this.completedSagas = completedSagas; }
        public int getFailedSagas() { return failedSagas; }
        public void setFailedSagas(int failedSagas) { this.failedSagas = failedSagas; }
        public String getPaymentId() { return paymentId; }
        public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
        public String getReservationId() { return reservationId; }
        public void setReservationId(String reservationId) { this.reservationId = reservationId; }
        public String getShipmentId() { return shipmentId; }
        public void setShipmentId(String shipmentId) { this.shipmentId = shipmentId; }
        public String getEstimatedDelivery() { return estimatedDelivery; }
        public void setEstimatedDelivery(String estimatedDelivery) { this.estimatedDelivery = estimatedDelivery; }
        public int getLoyaltyPointsAwarded() { return loyaltyPointsAwarded; }
        public void setLoyaltyPointsAwarded(int loyaltyPointsAwarded) { this.loyaltyPointsAwarded = loyaltyPointsAwarded; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public java.util.Set<String> getFailedSagaIds() { return failedSagaIds; }
        public void setFailedSagaIds(java.util.Set<String> failedSagaIds) { this.failedSagaIds = failedSagaIds; }
    }
    
    // Internal records for saga communication
    record PaymentValidation(String customerId, Double amount, String paymentMethod, boolean valid) {}
    record PaymentResult(String paymentId, String orderId, Double amount, String status) {}
    record InventoryCheck(java.util.List<OrderItem> items, boolean available) {}
    record InventoryReservation(String reservationId, String orderId, java.util.List<OrderItem> items) {}
    record ShippingCalculation(String address, Double cost, String method) {}
    record ShipmentCreation(String shipmentId, String orderId, String estimatedDelivery) {}
    record NotificationResult(String type, String customerId, String orderId, boolean sent) {}
    record LoyaltyResult(String customerId, int pointsAwarded) {}
}
