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

package com.firefly.transactional.saga.composition;

import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.saga.engine.SagaEngine;
import com.firefly.transactional.saga.engine.StepInputs;
import com.firefly.transactional.saga.registry.SagaBuilder;
import com.firefly.transactional.saga.registry.SagaDefinition;
import com.firefly.transactional.saga.registry.SagaRegistry;
import com.firefly.transactional.shared.engine.CompensationPolicy;
import com.firefly.transactional.saga.engine.step.StepHandler;
import com.firefly.transactional.saga.observability.SagaLoggerEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration test demonstrating end-to-end saga composition functionality.
 * This test simulates a realistic e-commerce order fulfillment workflow.
 */
class SagaCompositionIntegrationTest {

    @Mock
    private SagaEngine sagaEngine;
    
    @Mock
    private SagaRegistry sagaRegistry;
    
    private SagaCompositor sagaCompositor;
    private SagaLoggerEvents sagaEvents;
    
    // Simulated external services
    private final PaymentService paymentService = new PaymentService();
    private final InventoryService inventoryService = new InventoryService();
    private final ShippingService shippingService = new ShippingService();
    private final NotificationService notificationService = new NotificationService();
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        sagaEvents = new SagaLoggerEvents();
        sagaCompositor = new SagaCompositor(sagaEngine, sagaRegistry, sagaEvents);
        
        setupSagaDefinitions();
        setupSagaEngineResponses();
    }
    
    private void setupSagaDefinitions() {
        // Payment processing saga
        SagaDefinition paymentSaga = SagaBuilder.saga("payment-processing")
                .step("validate-payment")
                    .handler((StepHandler<OrderRequest, PaymentValidation>) (req, ctx) -> 
                        paymentService.validatePayment(req))
                    .add()
                .step("charge-payment")
                    .dependsOn("validate-payment")
                    .handler((StepHandler<PaymentValidation, PaymentResult>) (validation, ctx) -> 
                        paymentService.chargePayment(validation))
                    .add()
                .build();
        
        // Inventory reservation saga
        SagaDefinition inventorySaga = SagaBuilder.saga("inventory-reservation")
                .step("check-availability")
                    .handler((StepHandler<OrderRequest, InventoryCheck>) (req, ctx) -> 
                        inventoryService.checkAvailability(req))
                    .add()
                .step("reserve-items")
                    .dependsOn("check-availability")
                    .handler((StepHandler<InventoryCheck, ReservationResult>) (check, ctx) -> 
                        inventoryService.reserveItems(check))
                    .add()
                .build();
        
        // Shipping preparation saga
        SagaDefinition shippingSaga = SagaBuilder.saga("shipping-preparation")
                .step("calculate-shipping")
                    .handler((StepHandler<OrderRequest, ShippingCalculation>) (req, ctx) -> 
                        shippingService.calculateShipping(req))
                    .add()
                .step("prepare-shipment")
                    .dependsOn("calculate-shipping")
                    .handler((StepHandler<ShippingCalculation, ShipmentPreparation>) (calc, ctx) -> 
                        shippingService.prepareShipment(calc))
                    .add()
                .build();
        
        // Notification saga
        SagaDefinition notificationSaga = SagaBuilder.saga("notification-sending")
                .step("send-confirmation")
                    .handler((StepHandler<OrderRequest, NotificationResult>) (req, ctx) -> 
                        notificationService.sendConfirmation(req))
                    .add()
                .build();
        
        // Register sagas
        when(sagaRegistry.getSaga("payment-processing")).thenReturn(paymentSaga);
        when(sagaRegistry.getSaga("inventory-reservation")).thenReturn(inventorySaga);
        when(sagaRegistry.getSaga("shipping-preparation")).thenReturn(shippingSaga);
        when(sagaRegistry.getSaga("notification-sending")).thenReturn(notificationSaga);
    }
    
    private void setupSagaEngineResponses() {
        // Mock successful saga executions with realistic results
        when(sagaEngine.execute(any(SagaDefinition.class), any(StepInputs.class), any(SagaContext.class))).thenAnswer(invocation -> {
            SagaDefinition saga = invocation.getArgument(0);
            SagaContext context = invocation.getArgument(2);
            
            // Simulate saga execution based on saga name
            return switch (saga.name) {
                case "payment-processing" -> {
                    context.putVariable("shared.paymentId", "payment-12345");
                    context.putVariable("shared.chargedAmount", 99.99);
                    yield Mono.just(createSuccessfulResult(saga.name, context));
                }
                case "inventory-reservation" -> {
                    context.putVariable("shared.reservationId", "reservation-67890");
                    context.putVariable("shared.reservedItems", java.util.List.of("item1", "item2"));
                    yield Mono.just(createSuccessfulResult(saga.name, context));
                }
                case "shipping-preparation" -> {
                    context.putVariable("shared.shipmentId", "shipment-11111");
                    context.putVariable("shared.estimatedDelivery", "2025-01-15");
                    yield Mono.just(createSuccessfulResult(saga.name, context));
                }
                case "notification-sending" -> {
                    context.putVariable("shared.notificationsSent", 3);
                    yield Mono.just(createSuccessfulResult(saga.name, context));
                }
                default -> Mono.error(new RuntimeException("Unknown saga: " + saga.name));
            };
        });
    }
    
    @Test
    void testCompleteOrderFulfillmentWorkflow() {
        // Given: A complex order fulfillment composition
        SagaComposition composition = SagaCompositor.compose("order-fulfillment-workflow")
                .compensationPolicy(CompensationPolicy.GROUPED_PARALLEL)
                
                // Step 1: Process payment first
                .saga("payment-processing")
                    .withId("payment")
                    .withInput("orderId", "order-12345")
                    .withInput("amount", 99.99)
                    .withInput("customerId", "customer-456")
                    .add()
                
                // Step 2: Reserve inventory (depends on payment)
                .saga("inventory-reservation")
                    .withId("inventory")
                    .dependsOn("payment")
                    .withDataFrom("payment", "paymentId")
                    .withInput("orderId", "order-12345")
                    .add()
                
                // Step 3: Prepare shipping and send notifications in parallel
                .saga("shipping-preparation")
                    .withId("shipping")
                    .dependsOn("inventory")
                    .executeInParallelWith("notifications")
                    .withDataFrom("inventory", "reservationId")
                    .withInput("orderId", "order-12345")
                    .add()
                
                .saga("notification-sending")
                    .withId("notifications")
                    .dependsOn("payment")
                    .withDataFrom("payment", "paymentId")
                    .withDataFrom("payment", "chargedAmount", "amount")
                    .withInput("customerId", "customer-456")
                    .add()
                
                .build();
        
        SagaContext rootContext = new SagaContext("order-fulfillment-test");
        rootContext.putHeader("X-User-Id", "user-789");
        rootContext.putHeader("X-Request-Id", "req-12345");
        
        // When: Executing the composition
        StepVerifier.create(sagaCompositor.execute(composition, rootContext))
                .assertNext(result -> {
                    // Then: All sagas should complete successfully
                    assertTrue(result.isSuccess(), "Composition should succeed");
                    assertEquals("order-fulfillment-workflow", result.getCompositionName());
                    
                    // Verify all sagas completed
                    assertEquals(4, result.getCompletedSagaCount());
                    assertEquals(0, result.getFailedSagaCount());
                    assertEquals(0, result.getSkippedSagaCount());
                    
                    // Verify specific saga completions
                    assertTrue(result.isSagaCompleted("payment"));
                    assertTrue(result.isSagaCompleted("inventory"));
                    assertTrue(result.isSagaCompleted("shipping"));
                    assertTrue(result.isSagaCompleted("notifications"));
                    
                    // Verify shared data was propagated
                    Map<String, Object> sharedVars = result.getSharedVariables();
                    assertEquals("payment-12345", sharedVars.get("paymentId"));
                    assertEquals(99.99, sharedVars.get("chargedAmount"));
                    assertEquals("reservation-67890", sharedVars.get("reservationId"));
                    assertEquals("shipment-11111", sharedVars.get("shipmentId"));
                    assertEquals("2025-01-15", sharedVars.get("estimatedDelivery"));
                    assertEquals(3, sharedVars.get("notificationsSent"));
                    
                    // Verify execution duration is reasonable
                    assertTrue(result.getDuration().toMillis() >= 0);
                    
                    // Verify individual saga results are available
                    assertNotNull(result.getSagaResult("payment"));
                    assertNotNull(result.getSagaResult("inventory"));
                    assertNotNull(result.getSagaResult("shipping"));
                    assertNotNull(result.getSagaResult("notifications"));
                })
                .verifyComplete();
    }
    
    @Test
    void testCompositionWithOptionalSagaFailure() {
        // Given: A composition where an optional saga fails
        when(sagaEngine.execute(any(SagaDefinition.class), any(StepInputs.class), any(SagaContext.class))).thenAnswer(invocation -> {
            SagaDefinition saga = invocation.getArgument(0);
            SagaContext context = invocation.getArgument(2);
            
            if ("notification-sending".equals(saga.name)) {
                return Mono.error(new RuntimeException("Notification service unavailable"));
            }
            
            return Mono.just(createSuccessfulResult(saga.name, context));
        });
        
        SagaComposition composition = SagaCompositor.compose("resilient-order-processing")
                .saga("payment-processing")
                    .withId("payment")
                    .add()
                .saga("inventory-reservation")
                    .withId("inventory")
                    .dependsOn("payment")
                    .add()
                .saga("notification-sending")
                    .withId("notifications")
                    .dependsOn("payment")
                    .optional() // Mark as optional
                    .add()
                .build();
        
        SagaContext rootContext = new SagaContext("resilient-test");
        
        // When: Executing the composition
        StepVerifier.create(sagaCompositor.execute(composition, rootContext))
                .assertNext(result -> {
                    // Then: Composition should still succeed despite optional saga failure
                    assertTrue(result.isSuccess());
                    assertEquals(2, result.getCompletedSagaCount()); // payment and inventory
                    assertEquals(1, result.getFailedSagaCount()); // notifications failed
                    assertEquals(0, result.getSkippedSagaCount());
                    
                    assertTrue(result.isSagaCompleted("payment"));
                    assertTrue(result.isSagaCompleted("inventory"));
                    assertTrue(result.isSagaFailed("notifications"));
                })
                .verifyComplete();
    }
    
    private com.firefly.transactional.saga.core.SagaResult createSuccessfulResult(String sagaName, SagaContext context) {
        return com.firefly.transactional.saga.core.SagaResult.from(
                sagaName, context, java.util.Collections.emptyMap(), 
                java.util.Collections.emptyMap(), java.util.Collections.emptyList());
    }
    
    // Mock service classes for realistic simulation
    static class PaymentService {
        Mono<PaymentValidation> validatePayment(OrderRequest request) {
            return Mono.just(new PaymentValidation(request.orderId(), true));
        }
        
        Mono<PaymentResult> chargePayment(PaymentValidation validation) {
            return Mono.just(new PaymentResult("payment-12345", validation.orderId(), 99.99));
        }
    }
    
    static class InventoryService {
        Mono<InventoryCheck> checkAvailability(OrderRequest request) {
            return Mono.just(new InventoryCheck(request.orderId(), true));
        }
        
        Mono<ReservationResult> reserveItems(InventoryCheck check) {
            return Mono.just(new ReservationResult("reservation-67890", check.orderId()));
        }
    }
    
    static class ShippingService {
        Mono<ShippingCalculation> calculateShipping(OrderRequest request) {
            return Mono.just(new ShippingCalculation(request.orderId(), 9.99));
        }
        
        Mono<ShipmentPreparation> prepareShipment(ShippingCalculation calculation) {
            return Mono.just(new ShipmentPreparation("shipment-11111", calculation.orderId()));
        }
    }
    
    static class NotificationService {
        Mono<NotificationResult> sendConfirmation(OrderRequest request) {
            return Mono.just(new NotificationResult(request.customerId(), 3));
        }
    }
    
    // Data transfer objects
    record OrderRequest(String orderId, String customerId, double amount) {}
    record PaymentValidation(String orderId, boolean valid) {}
    record PaymentResult(String paymentId, String orderId, double amount) {}
    record InventoryCheck(String orderId, boolean available) {}
    record ReservationResult(String reservationId, String orderId) {}
    record ShippingCalculation(String orderId, double cost) {}
    record ShipmentPreparation(String shipmentId, String orderId) {}
    record NotificationResult(String customerId, int notificationsSent) {}
}
