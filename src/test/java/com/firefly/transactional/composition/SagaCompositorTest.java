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

package com.firefly.transactional.composition;

import com.firefly.transactional.core.SagaContext;
import com.firefly.transactional.core.SagaResult;
import com.firefly.transactional.engine.CompensationPolicy;
import com.firefly.transactional.engine.SagaEngine;
import com.firefly.transactional.engine.StepHandler;
import com.firefly.transactional.engine.StepInputs;
import com.firefly.transactional.observability.SagaEvents;
import com.firefly.transactional.registry.SagaBuilder;
import com.firefly.transactional.registry.SagaDefinition;
import com.firefly.transactional.registry.SagaRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for the SagaCompositor and related components.
 */
class SagaCompositorTest {

    @Mock
    private SagaEngine sagaEngine;
    
    @Mock
    private SagaRegistry sagaRegistry;
    
    private TestSagaEvents sagaEvents;
    private SagaCompositor sagaCompositor;
    
    // Test saga handlers
    private final StepHandler<String, String> paymentHandler = (input, ctx) -> {
        ctx.putVariable("paymentId", "payment-123");
        return Mono.just("Payment processed: " + input);
    };
    
    private final StepHandler<String, String> inventoryHandler = (input, ctx) -> {
        String paymentId = (String) ctx.getVariable("composition.paymentId");
        return Mono.just("Inventory reserved for payment: " + paymentId);
    };
    
    private final StepHandler<String, String> shippingHandler = (input, ctx) -> {
        return Mono.just("Shipping prepared");
    };
    
    private final StepHandler<String, String> notificationHandler = (input, ctx) -> {
        return Mono.just("Notification sent");
    };
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        sagaEvents = new TestSagaEvents();
        sagaCompositor = new SagaCompositor(sagaEngine, sagaRegistry, sagaEvents);
        
        setupMockSagas();
    }
    
    private void setupMockSagas() {
        // Create test saga definitions
        SagaDefinition paymentSaga = SagaBuilder.saga("payment-processing")
                .step("process-payment").handler(paymentHandler).add()
                .build();
        
        SagaDefinition inventorySaga = SagaBuilder.saga("inventory-reservation")
                .step("reserve-inventory").handler(inventoryHandler).add()
                .build();
        
        SagaDefinition shippingSaga = SagaBuilder.saga("shipping-preparation")
                .step("prepare-shipping").handler(shippingHandler).add()
                .build();
        
        SagaDefinition notificationSaga = SagaBuilder.saga("notification-sending")
                .step("send-notification").handler(notificationHandler).add()
                .build();
        
        // Mock saga registry
        when(sagaRegistry.getSaga("payment-processing")).thenReturn(paymentSaga);
        when(sagaRegistry.getSaga("inventory-reservation")).thenReturn(inventorySaga);
        when(sagaRegistry.getSaga("shipping-preparation")).thenReturn(shippingSaga);
        when(sagaRegistry.getSaga("notification-sending")).thenReturn(notificationSaga);
        
        // Mock saga engine execution
        when(sagaEngine.execute(eq(paymentSaga), any(StepInputs.class), any(SagaContext.class)))
                .thenAnswer(invocation -> {
                    SagaContext ctx = invocation.getArgument(2);
                    ctx.putVariable("shared.paymentId", "payment-123");
                    return Mono.just(createSuccessfulSagaResult("payment-processing", ctx));
                });
        
        when(sagaEngine.execute(eq(inventorySaga), any(StepInputs.class), any(SagaContext.class)))
                .thenAnswer(invocation -> {
                    SagaContext ctx = invocation.getArgument(2);
                    return Mono.just(createSuccessfulSagaResult("inventory-reservation", ctx));
                });
        
        when(sagaEngine.execute(eq(shippingSaga), any(StepInputs.class), any(SagaContext.class)))
                .thenAnswer(invocation -> {
                    SagaContext ctx = invocation.getArgument(2);
                    return Mono.just(createSuccessfulSagaResult("shipping-preparation", ctx));
                });
        
        when(sagaEngine.execute(eq(notificationSaga), any(StepInputs.class), any(SagaContext.class)))
                .thenAnswer(invocation -> {
                    SagaContext ctx = invocation.getArgument(2);
                    return Mono.just(createSuccessfulSagaResult("notification-sending", ctx));
                });
    }
    
    private SagaResult createSuccessfulSagaResult(String sagaName, SagaContext context) {
        return SagaResult.from(sagaName, context, java.util.Collections.emptyMap(), 
                              java.util.Collections.emptyMap(), java.util.Collections.emptyList());
    }
    
    @Test
    void testSequentialComposition() {
        // Given: A sequential composition
        SagaComposition composition = SagaCompositor.compose("order-fulfillment")
                .compensationPolicy(CompensationPolicy.STRICT_SEQUENTIAL)
                .saga("payment-processing")
                    .withInput("orderId", "order-123")
                    .add()
                .saga("inventory-reservation")
                    .dependsOn("payment-processing")
                    .withDataFrom("payment-processing", "paymentId")
                    .add()
                .saga("shipping-preparation")
                    .dependsOn("inventory-reservation")
                    .add()
                .build();
        
        SagaContext context = new SagaContext("test-correlation-id");
        
        // When: Executing the composition
        StepVerifier.create(sagaCompositor.execute(composition, context))
                .assertNext(result -> {
                    // Then: Composition should succeed
                    assertTrue(result.isSuccess());
                    assertEquals("order-fulfillment", result.getCompositionName());
                    assertEquals(3, result.getCompletedSagaCount());
                    assertEquals(0, result.getFailedSagaCount());
                    
                    // Verify execution order
                    assertTrue(result.isSagaCompleted("payment-processing"));
                    assertTrue(result.isSagaCompleted("inventory-reservation"));
                    assertTrue(result.isSagaCompleted("shipping-preparation"));
                })
                .verifyComplete();
        
        // Verify events were emitted
        assertTrue(sagaEvents.calls.stream().anyMatch(call -> call.contains("composition-started")));
        assertTrue(sagaEvents.calls.stream().anyMatch(call -> call.contains("composition-completed")));
    }
    
    @Test
    void testParallelComposition() {
        // Given: A composition with parallel execution
        SagaComposition composition = SagaCompositor.compose("parallel-fulfillment")
                .saga("payment-processing")
                    .withInput("orderId", "order-123")
                    .add()
                .saga("shipping-preparation")
                    .dependsOn("payment-processing")
                    .executeInParallelWith("notification-sending")
                    .add()
                .saga("notification-sending")
                    .dependsOn("payment-processing")
                    .add()
                .build();
        
        SagaContext context = new SagaContext("test-parallel");
        
        // When: Executing the composition
        StepVerifier.create(sagaCompositor.execute(composition, context))
                .assertNext(result -> {
                    // Then: All sagas should complete successfully
                    assertTrue(result.isSuccess());
                    assertEquals(3, result.getCompletedSagaCount());
                    
                    // Verify all sagas completed
                    assertTrue(result.isSagaCompleted("payment-processing"));
                    assertTrue(result.isSagaCompleted("shipping-preparation"));
                    assertTrue(result.isSagaCompleted("notification-sending"));
                })
                .verifyComplete();
    }
    
    @Test
    void testCompositionWithFailure() {
        // Given: A saga that will fail
        SagaDefinition failingSaga = SagaBuilder.saga("failing-saga")
                .step("fail-step").handler((StepHandler<String, String>) (input, ctx) -> 
                    Mono.error(new RuntimeException("Intentional failure")))
                .add()
                .build();
        
        when(sagaRegistry.getSaga("failing-saga")).thenReturn(failingSaga);
        when(sagaEngine.execute(eq(failingSaga), any(StepInputs.class), any(SagaContext.class)))
                .thenReturn(Mono.error(new RuntimeException("Saga execution failed")));
        
        SagaComposition composition = SagaCompositor.compose("failing-composition")
                .saga("payment-processing")
                    .add()
                .saga("failing-saga")
                    .dependsOn("payment-processing")
                    .add()
                .build();
        
        SagaContext context = new SagaContext("test-failure");
        
        // When: Executing the composition
        StepVerifier.create(sagaCompositor.execute(composition, context))
                .assertNext(result -> {
                    // Then: Composition should fail
                    assertFalse(result.isSuccess());
                    assertEquals(1, result.getCompletedSagaCount()); // Only payment should complete
                    assertEquals(1, result.getFailedSagaCount()); // failing-saga should fail
                })
                .verifyComplete();
    }
    
    @Test
    void testOptionalSagaFailure() {
        // Given: A composition with an optional saga that fails
        SagaDefinition optionalFailingSaga = SagaBuilder.saga("optional-failing-saga")
                .step("fail-step").handler((StepHandler<String, String>) (input, ctx) -> 
                    Mono.error(new RuntimeException("Optional failure")))
                .add()
                .build();
        
        when(sagaRegistry.getSaga("optional-failing-saga")).thenReturn(optionalFailingSaga);
        when(sagaEngine.execute(eq(optionalFailingSaga), any(StepInputs.class), any(SagaContext.class)))
                .thenReturn(Mono.error(new RuntimeException("Optional saga failed")));
        
        SagaComposition composition = SagaCompositor.compose("optional-failure-composition")
                .saga("payment-processing")
                    .add()
                .saga("optional-failing-saga")
                    .dependsOn("payment-processing")
                    .optional()
                    .add()
                .saga("shipping-preparation")
                    .dependsOn("payment-processing")
                    .add()
                .build();
        
        SagaContext context = new SagaContext("test-optional-failure");
        
        // When: Executing the composition
        StepVerifier.create(sagaCompositor.execute(composition, context))
                .assertNext(result -> {
                    // Then: Composition should still succeed
                    assertTrue(result.isSuccess());
                    assertEquals(2, result.getCompletedSagaCount()); // payment and shipping
                    assertEquals(1, result.getFailedSagaCount()); // optional saga failed
                })
                .verifyComplete();
    }
    
    @Test
    void testCompositionValidation() {
        // Given: An invalid composition with circular dependencies
        assertThrows(IllegalStateException.class, () -> {
            SagaCompositor.compose("invalid-composition")
                    .saga("saga-a")
                        .dependsOn("saga-b")
                        .add()
                    .saga("saga-b")
                        .dependsOn("saga-a")
                        .add()
                    .build();
        });
    }
    
    /**
     * Test implementation of SagaEvents for capturing events during tests.
     */
    static class TestSagaEvents implements SagaEvents {
        final List<String> calls = new CopyOnWriteArrayList<>();
        
        @Override
        public void onCompositionStarted(String compositionName, String compositionId) {
            calls.add("composition-started:" + compositionName + ":" + compositionId);
        }
        
        @Override
        public void onCompositionCompleted(String compositionName, String compositionId, boolean success, long latencyMs, int completedSagas, int failedSagas, int skippedSagas) {
            calls.add("composition-completed:" + compositionName + ":" + success + ":" + completedSagas + ":" + failedSagas + ":" + skippedSagas);
        }
        
        @Override
        public void onCompositionSagaStarted(String compositionName, String compositionId, String sagaId, String sagaName) {
            calls.add("saga-started:" + compositionName + ":" + sagaId + ":" + sagaName);
        }
        
        @Override
        public void onCompositionSagaCompleted(String compositionName, String compositionId, String sagaId, String sagaName, long latencyMs) {
            calls.add("saga-completed:" + compositionName + ":" + sagaId + ":" + sagaName);
        }
        
        @Override
        public void onCompositionSagaFailed(String compositionName, String compositionId, String sagaId, String sagaName, Throwable error, long latencyMs) {
            calls.add("saga-failed:" + compositionName + ":" + sagaId + ":" + sagaName + ":" + error.getMessage());
        }
    }
}
