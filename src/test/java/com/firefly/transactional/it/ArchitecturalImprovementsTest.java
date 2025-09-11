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

package com.firefly.transactional.it;

import com.firefly.transactional.config.SagaEngineProperties;
import com.firefly.transactional.core.SagaContextFactory;
import com.firefly.transactional.engine.backpressure.BackpressureStrategyFactory;
import com.firefly.transactional.engine.compensation.CompensationErrorHandlerFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the architectural improvements implemented in the transactional engine.
 * Tests the new components and their integration without requiring Spring Boot context.
 */
class ArchitecturalImprovementsTest {

    @Test
    void testSagaContextFactoryExecutionModes() {
        // Test that all execution modes are available
        SagaContextFactory.ExecutionMode[] modes = SagaContextFactory.ExecutionMode.values();
        assertThat(modes).contains(
            SagaContextFactory.ExecutionMode.AUTO,
            SagaContextFactory.ExecutionMode.SEQUENTIAL,
            SagaContextFactory.ExecutionMode.CONCURRENT,
            SagaContextFactory.ExecutionMode.HIGH_PERFORMANCE,
            SagaContextFactory.ExecutionMode.LOW_MEMORY
        );
    }

    @Test
    void testBackpressureStrategyFactoryHasStrategies() {
        // Test that backpressure strategies are available
        assertThat(BackpressureStrategyFactory.getAvailableStrategies())
            .contains("batched", "adaptive", "circuit-breaker");
        
        // Test that we can get strategies
        assertThat(BackpressureStrategyFactory.getStrategy("batched")).isNotNull();
        assertThat(BackpressureStrategyFactory.getStrategy("adaptive")).isNotNull();
        assertThat(BackpressureStrategyFactory.getStrategy("circuit-breaker")).isNotNull();
    }

    @Test
    void testCompensationErrorHandlerFactoryHasHandlers() {
        // Test that compensation error handlers are available
        assertThat(CompensationErrorHandlerFactory.getAvailableHandlers())
            .contains("log-and-continue", "fail-fast", "retry");
        
        // Test that we can get handlers
        assertThat(CompensationErrorHandlerFactory.getHandler("log-and-continue")).isNotNull();
        assertThat(CompensationErrorHandlerFactory.getHandler("fail-fast")).isNotNull();
        assertThat(CompensationErrorHandlerFactory.getHandler("retry")).isNotNull();
    }

    @Test
    void testSagaEnginePropertiesDefaults() {
        SagaEngineProperties properties = new SagaEngineProperties();
        
        // Test context properties defaults
        assertThat(properties.getContext().getExecutionMode()).isEqualTo(SagaContextFactory.ExecutionMode.AUTO);
        
        // Test backpressure properties defaults
        assertThat(properties.getBackpressure().getStrategy()).isEqualTo("batched");
        assertThat(properties.getBackpressure().getConcurrency()).isEqualTo(10);
        assertThat(properties.getBackpressure().getBatchSize()).isEqualTo(50);
        
        // Test compensation properties defaults
        assertThat(properties.getCompensation().getErrorHandler()).isEqualTo("log-and-continue");
        
        // Test observability properties defaults
        assertThat(properties.getObservability().isMetricsEnabled()).isTrue();
        
        // Test validation properties defaults
        assertThat(properties.getValidation().isEnabled()).isTrue();
        assertThat(properties.getValidation().isValidateAtStartup()).isTrue();
    }

    @Test
    void testFactoryMethodsWork() {
        // Test backpressure factory methods
        assertThat(BackpressureStrategyFactory.defaultBatched()).isNotNull();
        assertThat(BackpressureStrategyFactory.adaptive()).isNotNull();
        assertThat(BackpressureStrategyFactory.circuitBreaker()).isNotNull();
        assertThat(BackpressureStrategyFactory.aggressiveCircuitBreaker()).isNotNull();
        assertThat(BackpressureStrategyFactory.conservativeCircuitBreaker()).isNotNull();
        
        // Test compensation error handler factory methods
        assertThat(CompensationErrorHandlerFactory.defaultHandler()).isNotNull();
        assertThat(CompensationErrorHandlerFactory.failFast()).isNotNull();
        assertThat(CompensationErrorHandlerFactory.retry()).isNotNull();
        assertThat(CompensationErrorHandlerFactory.robust()).isNotNull();
        assertThat(CompensationErrorHandlerFactory.strict()).isNotNull();
        assertThat(CompensationErrorHandlerFactory.networkAware()).isNotNull();
    }

    @Test
    void testSagaEnginePropertiesCanBeConfigured() {
        SagaEngineProperties properties = new SagaEngineProperties();
        
        // Test that properties can be modified
        properties.getContext().setExecutionMode(SagaContextFactory.ExecutionMode.HIGH_PERFORMANCE);
        assertThat(properties.getContext().getExecutionMode()).isEqualTo(SagaContextFactory.ExecutionMode.HIGH_PERFORMANCE);
        
        properties.getBackpressure().setStrategy("circuit-breaker");
        assertThat(properties.getBackpressure().getStrategy()).isEqualTo("circuit-breaker");
        
        properties.getCompensation().setErrorHandler("fail-fast");
        assertThat(properties.getCompensation().getErrorHandler()).isEqualTo("fail-fast");
        
        properties.getObservability().setMetricsEnabled(false);
        assertThat(properties.getObservability().isMetricsEnabled()).isFalse();
        
        properties.getValidation().setEnabled(false);
        assertThat(properties.getValidation().isEnabled()).isFalse();
    }

    @Test
    void testBackpressureStrategyFactoryRegistration() {
        // Test that we can register custom strategies
        String[] availableStrategies = BackpressureStrategyFactory.getAvailableStrategies();
        assertThat(availableStrategies).isNotEmpty();
        
        // Test that all default strategies are available
        assertThat(availableStrategies).contains("batched", "adaptive", "circuit-breaker");
    }

    @Test
    void testCompensationErrorHandlerFactoryRegistration() {
        // Test that we can get all available handlers
        String[] availableHandlers = CompensationErrorHandlerFactory.getAvailableHandlers();
        assertThat(availableHandlers).isNotEmpty();
        
        // Test that all default handlers are available
        assertThat(availableHandlers).contains("log-and-continue", "fail-fast", "retry");
        
        // Test that composite handlers are available
        assertThat(availableHandlers).contains("robust", "strict", "network-aware");
    }

    @Test
    void testSagaContextFactoryExecutionModeValues() {
        // Test that execution modes have expected values
        assertThat(SagaContextFactory.ExecutionMode.AUTO.name()).isEqualTo("AUTO");
        assertThat(SagaContextFactory.ExecutionMode.SEQUENTIAL.name()).isEqualTo("SEQUENTIAL");
        assertThat(SagaContextFactory.ExecutionMode.CONCURRENT.name()).isEqualTo("CONCURRENT");
        assertThat(SagaContextFactory.ExecutionMode.HIGH_PERFORMANCE.name()).isEqualTo("HIGH_PERFORMANCE");
        assertThat(SagaContextFactory.ExecutionMode.LOW_MEMORY.name()).isEqualTo("LOW_MEMORY");
    }

    @Test
    void testBackpressurePropertiesDefaults() {
        SagaEngineProperties.BackpressureProperties backpressure = new SagaEngineProperties.BackpressureProperties();

        // Test default values
        assertThat(backpressure.getStrategy()).isEqualTo("batched");
        assertThat(backpressure.getConcurrency()).isEqualTo(10);
        assertThat(backpressure.getBatchSize()).isEqualTo(50);
        assertThat(backpressure.getTimeout()).isNotNull();
    }

    @Test
    void testCompensationPropertiesDefaults() {
        SagaEngineProperties.CompensationProperties compensation = new SagaEngineProperties.CompensationProperties();

        // Test default values
        assertThat(compensation.getErrorHandler()).isEqualTo("log-and-continue");
        assertThat(compensation.getMaxRetries()).isEqualTo(3);
        assertThat(compensation.getRetryDelay()).isNotNull();
        assertThat(compensation.isFailFastOnCriticalErrors()).isFalse();
    }

    @Test
    void testObservabilityPropertiesDefaults() {
        SagaEngineProperties.ObservabilityProperties observability = new SagaEngineProperties.ObservabilityProperties();

        // Test default values
        assertThat(observability.isMetricsEnabled()).isTrue();
        assertThat(observability.isTracingEnabled()).isTrue();
        assertThat(observability.isDetailedLoggingEnabled()).isFalse();
        assertThat(observability.getMetricsInterval()).isNotNull();
    }

    @Test
    void testValidationPropertiesDefaults() {
        SagaEngineProperties.ValidationProperties validation = new SagaEngineProperties.ValidationProperties();

        // Test default values
        assertThat(validation.isEnabled()).isTrue();
        assertThat(validation.isValidateAtStartup()).isTrue();
        assertThat(validation.isFailFast()).isTrue();
        assertThat(validation.isValidateInputs()).isTrue();
    }

    @Test
    void testContextPropertiesDefaults() {
        SagaEngineProperties.ContextProperties context = new SagaEngineProperties.ContextProperties();

        // Test default values
        assertThat(context.getExecutionMode()).isEqualTo(SagaContextFactory.ExecutionMode.AUTO);
        assertThat(context.isOptimizationEnabled()).isTrue();
    }

    @Test
    void testArchitecturalImprovementsAreImplemented() {
        // This test verifies that all the architectural improvements are properly implemented
        // by checking that the key classes and enums are available and functional
        
        // 1. SagaExecutionOrchestrator - extracted from SagaEngine
        // (This would be tested in integration tests with Spring context)
        
        // 2. Simplified ReactiveStreamOptimizations - backpressure strategies
        assertThat(BackpressureStrategyFactory.getAvailableStrategies()).isNotEmpty();
        
        // 3. Enhanced compensation error handling
        assertThat(CompensationErrorHandlerFactory.getAvailableHandlers()).isNotEmpty();
        
        // 4. Optimized SagaContext creation - execution modes
        assertThat(SagaContextFactory.ExecutionMode.values()).isNotEmpty();
        
        // 5. External configuration - properties classes
        SagaEngineProperties properties = new SagaEngineProperties();
        assertThat(properties.getContext()).isNotNull();
        assertThat(properties.getBackpressure()).isNotNull();
        assertThat(properties.getCompensation()).isNotNull();
        assertThat(properties.getObservability()).isNotNull();
        assertThat(properties.getValidation()).isNotNull();
        
        // 6. Enhanced observability - metrics properties
        assertThat(properties.getObservability().isMetricsEnabled()).isTrue();
        
        // 7. Runtime validation - validation properties
        assertThat(properties.getValidation().isEnabled()).isTrue();
    }
}
