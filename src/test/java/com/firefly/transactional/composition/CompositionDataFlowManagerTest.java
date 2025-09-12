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
import com.firefly.transactional.core.StepStatus;
import com.firefly.transactional.engine.StepInputs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CompositionDataFlowManager.
 */
class CompositionDataFlowManagerTest {

    private CompositionDataFlowManager dataFlowManager;
    private SagaCompositionContext compositionContext;
    private SagaContext rootContext;

    @BeforeEach
    void setUp() {
        dataFlowManager = new CompositionDataFlowManager();
        rootContext = new SagaContext("test-correlation");
        compositionContext = new SagaCompositionContext("test-composition", rootContext);
    }

    @Test
    void testPrepareInputsWithStaticInputs() {
        // Given: A saga with static inputs
        StepInputs staticInputs = StepInputs.builder()
                .forStepId("orderId", "order-123")
                .forStepId("amount", 100.0)
                .build();

        SagaComposition.CompositionSaga saga = new SagaComposition.CompositionSaga(
                "test-saga", "test-saga-id", Set.of(), Set.of(),
                staticInputs, Map.of(), ctx -> true, false, 0);

        // When: Preparing inputs
        StepInputs result = dataFlowManager.prepareInputsForSaga(saga, compositionContext);

        // Then: Static inputs should be preserved
        Map<String, Object> materialized = result.materializeAll(rootContext);
        assertEquals("order-123", materialized.get("orderId"));
        assertEquals(100.0, materialized.get("amount"));
    }

    @Test
    void testPrepareInputsWithDataMapping() {
        // Given: A completed saga with results
        SagaContext paymentContext = new SagaContext("payment-context");
        paymentContext.putVariable("paymentId", "payment-123");
        
        SagaResult paymentResult = createSagaResultWithStepResult("payment-saga", "process-payment", "payment-result-data");
        compositionContext.recordSagaResult("payment-saga", paymentResult, paymentContext);

        // And: A saga that maps data from the payment saga
        Map<String, SagaComposition.DataMapping> dataMappings = Map.of(
                "paymentId", new SagaComposition.DataMapping("payment-saga", "paymentId", "paymentId")
        );

        SagaComposition.CompositionSaga saga = new SagaComposition.CompositionSaga(
                "inventory-saga", "inventory-saga-id", Set.of("payment-saga"), Set.of(),
                StepInputs.builder().build(), dataMappings, ctx -> true, false, 0);

        // When: Preparing inputs
        StepInputs result = dataFlowManager.prepareInputsForSaga(saga, compositionContext);

        // Then: Data should be mapped from the payment saga
        Map<String, Object> materialized = result.materializeAll(rootContext);
        assertEquals("payment-123", materialized.get("paymentId"));
    }

    @Test
    void testPrepareInputsWithSharedVariables() {
        // Given: Shared variables in the composition context
        compositionContext.setSharedVariable("userId", "user-456");
        compositionContext.setSharedVariable("sessionId", "session-789");

        SagaComposition.CompositionSaga saga = new SagaComposition.CompositionSaga(
                "test-saga", "test-saga-id", Set.of(), Set.of(),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        // When: Preparing inputs
        StepInputs result = dataFlowManager.prepareInputsForSaga(saga, compositionContext);

        // Then: Shared variables should be included with "shared." prefix
        Map<String, Object> materialized = result.materializeAll(rootContext);
        assertEquals("user-456", materialized.get("shared.userId"));
        assertEquals("session-789", materialized.get("shared.sessionId"));
    }

    @Test
    void testPropagateContextToSaga() {
        // Given: Root context with headers and shared variables
        rootContext.putHeader("X-User-Id", "user-123");
        rootContext.putHeader("X-Trace-Id", "trace-456");
        compositionContext.setSharedVariable("globalConfig", "config-value");

        SagaComposition.CompositionSaga saga = new SagaComposition.CompositionSaga(
                "test-saga", "test-saga-id", Set.of(), Set.of(),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        SagaContext sagaContext = new SagaContext("saga-context");

        // When: Propagating context
        dataFlowManager.propagateContextToSaga(sagaContext, compositionContext, saga);

        // Then: Headers should be copied
        assertEquals("user-123", sagaContext.headers().get("X-User-Id"));
        assertEquals("trace-456", sagaContext.headers().get("X-Trace-Id"));

        // And: Composition-specific headers should be added
        assertEquals(compositionContext.getCompositionId(), sagaContext.headers().get("X-Composition-Id"));
        assertEquals("test-composition", sagaContext.headers().get("X-Composition-Name"));
        assertEquals("test-saga-id", sagaContext.headers().get("X-Saga-Id"));

        // And: Shared variables should be copied with "composition." prefix
        assertEquals("config-value", sagaContext.getVariable("composition.globalConfig"));
    }

    @Test
    void testExtractSharedDataFromSaga() {
        // Given: A saga context with shared variables
        SagaContext sagaContext = new SagaContext("saga-context");
        sagaContext.putVariable("shared.extractedData", "extracted-value");
        sagaContext.putVariable("private.data", "private-value");

        SagaResult sagaResult = createSagaResultWithStepResult("test-saga", "test-step", "step-result");

        SagaComposition.CompositionSaga saga = new SagaComposition.CompositionSaga(
                "test-saga", "test-saga-id", Set.of(), Set.of(),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        // When: Extracting shared data
        dataFlowManager.extractSharedDataFromSaga(sagaResult, sagaContext, compositionContext, saga);

        // Then: Only variables with "shared." prefix should be extracted
        assertEquals("extracted-value", compositionContext.getSharedVariable("extractedData"));
        assertNull(compositionContext.getSharedVariable("private.data"));
    }

    @Test
    void testDataMappingWithTransformation() {
        // Given: A saga result with numeric data
        SagaContext sourceContext = new SagaContext("source-context");
        sourceContext.putVariable("amount", 100);
        
        SagaResult sourceResult = createSagaResultWithStepResult("source-saga", "calculate", 100);
        compositionContext.recordSagaResult("source-saga", sourceResult, sourceContext);

        // And: A data mapping with transformation (double the amount)
        Map<String, SagaComposition.DataMapping> dataMappings = Map.of(
                "doubledAmount", new SagaComposition.DataMapping(
                        "source-saga", "amount", "doubledAmount", 
                        value -> ((Integer) value) * 2)
        );

        SagaComposition.CompositionSaga saga = new SagaComposition.CompositionSaga(
                "target-saga", "target-saga-id", Set.of("source-saga"), Set.of(),
                StepInputs.builder().build(), dataMappings, ctx -> true, false, 0);

        // When: Preparing inputs
        StepInputs result = dataFlowManager.prepareInputsForSaga(saga, compositionContext);

        // Then: Transformed value should be available
        Map<String, Object> materialized = result.materializeAll(rootContext);
        assertEquals(200, materialized.get("doubledAmount"));
    }

    @Test
    void testDataMappingFromStepResult() {
        // Given: A saga with step results
        SagaResult sourceResult = createSagaResultWithStepResult("source-saga", "process-data", "step-output");
        compositionContext.recordSagaResult("source-saga", sourceResult, new SagaContext("source-context"));

        // And: A data mapping that references the step ID
        Map<String, SagaComposition.DataMapping> dataMappings = Map.of(
                "processedData", new SagaComposition.DataMapping("source-saga", "process-data", "processedData")
        );

        SagaComposition.CompositionSaga saga = new SagaComposition.CompositionSaga(
                "target-saga", "target-saga-id", Set.of("source-saga"), Set.of(),
                StepInputs.builder().build(), dataMappings, ctx -> true, false, 0);

        // When: Preparing inputs
        StepInputs result = dataFlowManager.prepareInputsForSaga(saga, compositionContext);

        // Then: Step result should be mapped
        Map<String, Object> materialized = result.materializeAll(rootContext);
        assertEquals("step-output", materialized.get("processedData"));
    }

    private SagaResult createSagaResultWithStepResult(String sagaName, String stepId, Object stepResult) {
        SagaContext testContext = new SagaContext("test-correlation");
        testContext.setStatus(stepId, StepStatus.DONE);
        testContext.putResult(stepId, stepResult);
        testContext.incrementAttempts(stepId);
        testContext.setLatency(stepId, 100L);
        testContext.markStepStarted(stepId, Instant.now());

        return SagaResult.from(
                sagaName,
                testContext,
                Map.of(), // no compensated flags
                Map.of(), // no step errors
                List.of(stepId) // step IDs
        );
    }
}
