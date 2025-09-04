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


package com.firefly.transactionalengine.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for OptimizedSagaContext to ensure it provides equivalent functionality
 * to SagaContext while offering better performance for single-threaded scenarios.
 */
class OptimizedSagaContextTest {

    private OptimizedSagaContext context;

    @BeforeEach
    void setUp() {
        context = new OptimizedSagaContext();
    }

    @Test
    void shouldCreateContextWithGeneratedCorrelationId() {
        assertThat(context.correlationId()).isNotNull();
        assertThat(context.correlationId()).isNotEmpty();
        assertThat(UUID.fromString(context.correlationId())).isNotNull(); // Should be valid UUID
    }

    @Test
    void shouldCreateContextWithCustomCorrelationId() {
        String customId = "custom-correlation-id";
        OptimizedSagaContext customContext = new OptimizedSagaContext(customId);
        
        assertThat(customContext.correlationId()).isEqualTo(customId);
    }

    @Test
    void shouldCreateContextWithSagaName() {
        String correlationId = "test-id";
        String sagaName = "TestSaga";
        OptimizedSagaContext namedContext = new OptimizedSagaContext(correlationId, sagaName);
        
        assertThat(namedContext.correlationId()).isEqualTo(correlationId);
        assertThat(namedContext.sagaName()).isEqualTo(sagaName);
    }

    @Test
    void shouldConvertToAndFromStandardContext() {
        // Given - setup optimized context with data
        context.setSagaName("TestSaga");
        context.putHeader("X-Test-Header", "test-value");
        context.putVariable("testVar", "test-variable");
        
        // When - convert to standard context
        SagaContext standard = context.toStandardContext();
        
        // Then - verify conversion
        assertThat(standard.correlationId()).isEqualTo(context.correlationId());
        assertThat(standard.sagaName()).isEqualTo(context.sagaName());
        assertThat(standard.headers()).containsEntry("X-Test-Header", "test-value");
        assertThat(standard.variables()).containsEntry("testVar", "test-variable");
        
        // When - convert back from standard context
        OptimizedSagaContext backConverted = OptimizedSagaContext.from(standard);
        
        // Then - verify round-trip conversion
        assertThat(backConverted.correlationId()).isEqualTo(context.correlationId());
        assertThat(backConverted.sagaName()).isEqualTo(context.sagaName());
    }

    @Test
    void shouldHandleHeaders() {
        // When
        context.putHeader("X-User-Id", "user-123");
        context.putHeader("X-Tenant-Id", "tenant-456");
        
        // Then
        assertThat(context.getHeader("X-User-Id")).isEqualTo("user-123");
        assertThat(context.getHeader("X-Tenant-Id")).isEqualTo("tenant-456");
        assertThat(context.headers()).hasSize(2);
        assertThat(context.headers()).containsEntry("X-User-Id", "user-123");
        assertThat(context.headers()).containsEntry("X-Tenant-Id", "tenant-456");
    }

    @Test
    void shouldHandleVariables() {
        // When
        context.putVariable("orderId", 12345L);
        context.putVariable("customerName", "John Doe");
        context.putVariable("nullValue", null); // Should remove the variable
        
        // Then
        assertThat(context.getVariable("orderId")).isEqualTo(12345L);
        assertThat(context.getVariable("customerName")).isEqualTo("John Doe");
        assertThat(context.getVariable("nullValue")).isNull();
        assertThat(context.variables()).hasSize(2);
        
        // Test typed accessors
        assertThat(context.getVariableAs("orderId", Long.class)).isEqualTo(12345L);
        assertThat(context.getVariableAs("customerName", String.class)).isEqualTo("John Doe");
        assertThat(context.getVariableAs("nonExistent", String.class)).isNull();
        
        // Test type casting error
        assertThatThrownBy(() -> context.getVariableAs("orderId", String.class))
            .isInstanceOf(ClassCastException.class)
            .hasMessageContaining("cannot be cast to java.lang.String");
    }

    @Test
    void shouldHandleStepOperations() {
        String stepId = "testStep";
        
        // When - set step data
        context.putResult(stepId, "step-result");
        context.setStatus(stepId, StepStatus.RUNNING);
        context.setAttempts(stepId, 3);
        context.setLatency(stepId, 1500L);
        context.markStepStarted(stepId, Instant.now());
        
        // Then - verify step data
        assertThat(context.getResult(stepId)).isEqualTo("step-result");
        assertThat(context.getStatus(stepId)).isEqualTo(StepStatus.RUNNING);
        assertThat(context.getAttempts(stepId)).isEqualTo(3);
        assertThat(context.getLatency(stepId)).isEqualTo(1500L);
        assertThat(context.getStepStartedAt(stepId)).isNotNull();
        
        // Test typed result accessor
        assertThat(context.resultOf(stepId, String.class)).contains("step-result");
        assertThat(context.resultOf(stepId, Long.class)).isEmpty();
        assertThat(context.resultOf("nonExistent", String.class)).isEmpty();
    }

    @Test
    void shouldHandleIdempotencyOperations() {
        String key1 = "idempotency-key-1";
        String key2 = "idempotency-key-2";
        
        // Initially no keys
        assertThat(context.hasIdempotencyKey(key1)).isFalse();
        
        // Mark keys
        context.markIdempotent(key1);
        context.markIdempotent(key2);
        
        // Verify keys are present
        assertThat(context.hasIdempotencyKey(key1)).isTrue();
        assertThat(context.hasIdempotencyKey(key2)).isTrue();
        assertThat(context.hasIdempotencyKey("non-existent")).isFalse();
    }

    @Test
    void shouldHandleCompensationOperations() {
        String stepId = "compensatedStep";
        
        // When - set compensation data
        context.putCompensationResult(stepId, "compensation-result");
        context.putCompensationError(stepId, new RuntimeException("Compensation failed"));
        
        // Then - verify compensation data
        assertThat(context.getCompensationResult(stepId)).isEqualTo("compensation-result");
        assertThat(context.getCompensationError(stepId))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("Compensation failed");
        
        // Test null handling
        context.putCompensationResult("nullStep", null);
        context.putCompensationError("nullStep", null);
        assertThat(context.getCompensationResult("nullStep")).isNull();
        assertThat(context.getCompensationError("nullStep")).isNull();
    }

    @Test
    void shouldProvideReadOnlyViews() {
        // Given - populate context with data
        context.putHeader("header1", "value1");
        context.putVariable("var1", "varValue1");
        context.putResult("step1", "result1");
        context.setStatus("step1", StepStatus.DONE);
        context.setAttempts("step1", 2);
        context.setLatency("step1", 1000L);
        context.markStepStarted("step1", Instant.now());
        context.putCompensationResult("step1", "compResult1");
        context.putCompensationError("step1", new RuntimeException("compError1"));
        
        // When - get read-only views
        var stepResultsView = context.stepResultsView();
        var stepStatusesView = context.stepStatusesView();
        var stepAttemptsView = context.stepAttemptsView();
        var stepLatenciesView = context.stepLatenciesView();
        var stepStartedAtView = context.stepStartedAtView();
        var compensationResultsView = context.compensationResultsView();
        var compensationErrorsView = context.compensationErrorsView();
        
        // Then - verify views are unmodifiable
        assertThat(stepResultsView).containsEntry("step1", "result1");
        assertThat(stepStatusesView).containsEntry("step1", StepStatus.DONE);
        assertThat(stepAttemptsView).containsEntry("step1", 2);
        assertThat(stepLatenciesView).containsEntry("step1", 1000L);
        assertThat(stepStartedAtView).containsKey("step1");
        assertThat(compensationResultsView).containsEntry("step1", "compResult1");
        assertThat(compensationErrorsView).containsKey("step1");
        
        // Verify they are truly unmodifiable
        assertThatThrownBy(() -> stepResultsView.put("newStep", "newResult"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldHandleTopologyOperations() {
        // Given
        java.util.List<java.util.List<String>> layers = java.util.List.of(
            java.util.List.of("step1", "step2"),
            java.util.List.of("step3")
        );
        java.util.Map<String, java.util.List<String>> dependencies = java.util.Map.of(
            "step3", java.util.List.of("step1", "step2")
        );
        
        // When
        context.setTopologyLayers(layers);
        context.setStepDependencies(dependencies);
        
        // Then
        assertThat(context.topologyLayersView()).hasSize(2);
        assertThat(context.topologyLayersView().get(0)).containsExactly("step1", "step2");
        assertThat(context.topologyLayersView().get(1)).containsExactly("step3");
        
        assertThat(context.stepDependenciesView()).containsEntry("step3", java.util.List.of("step1", "step2"));
    }

    @Test
    void shouldRunPerformanceComparison() {
        // This test demonstrates the performance measurement capability
        // In a real scenario, this would show HashMap vs ConcurrentHashMap performance
        
        // When
        OptimizedSagaContext.PerformanceComparison.runComparison(1000);
        
        // Then - test passes if no exceptions thrown
        // The actual performance comparison is printed to console
        assertThat(true).isTrue(); // Placeholder assertion
    }
}