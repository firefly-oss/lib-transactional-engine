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

package com.firefly.transactionalengine.config;

import com.firefly.transactionalengine.core.SagaContextFactory;
import com.firefly.transactionalengine.engine.SagaEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class SagaEnginePropertiesTest {

    private SagaEngineProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SagaEngineProperties();
    }

    @Test
    void testDefaultValues() {
        assertThat(properties.getCompensationPolicy()).isEqualTo(SagaEngine.CompensationPolicy.STRICT_SEQUENTIAL);
        assertThat(properties.isAutoOptimizationEnabled()).isTrue();
        assertThat(properties.getDefaultTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getMaxConcurrentSagas()).isEqualTo(100);
    }

    @Test
    void testBasicPropertySettersAndGetters() {
        properties.setCompensationPolicy(SagaEngine.CompensationPolicy.BEST_EFFORT_PARALLEL);
        properties.setAutoOptimizationEnabled(false);
        properties.setDefaultTimeout(Duration.ofMinutes(10));
        properties.setMaxConcurrentSagas(200);

        assertThat(properties.getCompensationPolicy()).isEqualTo(SagaEngine.CompensationPolicy.BEST_EFFORT_PARALLEL);
        assertThat(properties.isAutoOptimizationEnabled()).isFalse();
        assertThat(properties.getDefaultTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.getMaxConcurrentSagas()).isEqualTo(200);
    }

    @Test
    void testContextPropertiesDefaults() {
        SagaEngineProperties.ContextProperties context = properties.getContext();
        
        assertThat(context.getExecutionMode()).isEqualTo(SagaContextFactory.ExecutionMode.AUTO);
        assertThat(context.isOptimizationEnabled()).isTrue();
    }

    @Test
    void testContextPropertiesSettersAndGetters() {
        SagaEngineProperties.ContextProperties context = properties.getContext();
        
        context.setExecutionMode(SagaContextFactory.ExecutionMode.SEQUENTIAL);
        context.setOptimizationEnabled(false);

        assertThat(context.getExecutionMode()).isEqualTo(SagaContextFactory.ExecutionMode.SEQUENTIAL);
        assertThat(context.isOptimizationEnabled()).isFalse();
    }

    @Test
    void testBackpressurePropertiesDefaults() {
        SagaEngineProperties.BackpressureProperties backpressure = properties.getBackpressure();
        
        assertThat(backpressure.getStrategy()).isEqualTo("batched");
        assertThat(backpressure.getConcurrency()).isEqualTo(10);
        assertThat(backpressure.getBatchSize()).isEqualTo(50);
        assertThat(backpressure.getTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void testBackpressurePropertiesSettersAndGetters() {
        SagaEngineProperties.BackpressureProperties backpressure = properties.getBackpressure();
        
        backpressure.setStrategy("adaptive");
        backpressure.setConcurrency(20);
        backpressure.setBatchSize(100);
        backpressure.setTimeout(Duration.ofSeconds(60));

        assertThat(backpressure.getStrategy()).isEqualTo("adaptive");
        assertThat(backpressure.getConcurrency()).isEqualTo(20);
        assertThat(backpressure.getBatchSize()).isEqualTo(100);
        assertThat(backpressure.getTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void testCompensationPropertiesDefaults() {
        SagaEngineProperties.CompensationProperties compensation = properties.getCompensation();
        
        assertThat(compensation.getErrorHandler()).isEqualTo("log-and-continue");
        assertThat(compensation.getMaxRetries()).isEqualTo(3);
        assertThat(compensation.getRetryDelay()).isEqualTo(Duration.ofMillis(100));
        assertThat(compensation.isFailFastOnCriticalErrors()).isFalse();
    }

    @Test
    void testCompensationPropertiesSettersAndGetters() {
        SagaEngineProperties.CompensationProperties compensation = properties.getCompensation();
        
        compensation.setErrorHandler("fail-fast");
        compensation.setMaxRetries(5);
        compensation.setRetryDelay(Duration.ofMillis(500));
        compensation.setFailFastOnCriticalErrors(true);

        assertThat(compensation.getErrorHandler()).isEqualTo("fail-fast");
        assertThat(compensation.getMaxRetries()).isEqualTo(5);
        assertThat(compensation.getRetryDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(compensation.isFailFastOnCriticalErrors()).isTrue();
    }

    @Test
    void testObservabilityPropertiesDefaults() {
        SagaEngineProperties.ObservabilityProperties observability = properties.getObservability();
        
        assertThat(observability.isMetricsEnabled()).isTrue();
        assertThat(observability.isTracingEnabled()).isTrue();
        assertThat(observability.isDetailedLoggingEnabled()).isFalse();
        assertThat(observability.getMetricsInterval()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void testObservabilityPropertiesSettersAndGetters() {
        SagaEngineProperties.ObservabilityProperties observability = properties.getObservability();
        
        observability.setMetricsEnabled(false);
        observability.setTracingEnabled(false);
        observability.setDetailedLoggingEnabled(true);
        observability.setMetricsInterval(Duration.ofSeconds(60));

        assertThat(observability.isMetricsEnabled()).isFalse();
        assertThat(observability.isTracingEnabled()).isFalse();
        assertThat(observability.isDetailedLoggingEnabled()).isTrue();
        assertThat(observability.getMetricsInterval()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void testValidationPropertiesDefaults() {
        SagaEngineProperties.ValidationProperties validation = properties.getValidation();
        
        assertThat(validation.isEnabled()).isTrue();
        assertThat(validation.isValidateAtStartup()).isTrue();
        assertThat(validation.isValidateInputs()).isTrue();
        assertThat(validation.isFailFast()).isTrue();
    }

    @Test
    void testValidationPropertiesSettersAndGetters() {
        SagaEngineProperties.ValidationProperties validation = properties.getValidation();
        
        validation.setEnabled(false);
        validation.setValidateAtStartup(false);
        validation.setValidateInputs(false);
        validation.setFailFast(false);

        assertThat(validation.isEnabled()).isFalse();
        assertThat(validation.isValidateAtStartup()).isFalse();
        assertThat(validation.isValidateInputs()).isFalse();
        assertThat(validation.isFailFast()).isFalse();
    }

    @Test
    void testNestedPropertiesSettersAndGetters() {
        // Test setting nested properties objects
        SagaEngineProperties.ContextProperties newContext = new SagaEngineProperties.ContextProperties();
        newContext.setExecutionMode(SagaContextFactory.ExecutionMode.HIGH_PERFORMANCE);
        properties.setContext(newContext);

        SagaEngineProperties.BackpressureProperties newBackpressure = new SagaEngineProperties.BackpressureProperties();
        newBackpressure.setStrategy("circuit-breaker");
        properties.setBackpressure(newBackpressure);

        SagaEngineProperties.CompensationProperties newCompensation = new SagaEngineProperties.CompensationProperties();
        newCompensation.setErrorHandler("robust");
        properties.setCompensation(newCompensation);

        SagaEngineProperties.ObservabilityProperties newObservability = new SagaEngineProperties.ObservabilityProperties();
        newObservability.setMetricsEnabled(false);
        properties.setObservability(newObservability);

        SagaEngineProperties.ValidationProperties newValidation = new SagaEngineProperties.ValidationProperties();
        newValidation.setEnabled(false);
        properties.setValidation(newValidation);

        assertThat(properties.getContext().getExecutionMode()).isEqualTo(SagaContextFactory.ExecutionMode.HIGH_PERFORMANCE);
        assertThat(properties.getBackpressure().getStrategy()).isEqualTo("circuit-breaker");
        assertThat(properties.getCompensation().getErrorHandler()).isEqualTo("robust");
        assertThat(properties.getObservability().isMetricsEnabled()).isFalse();
        assertThat(properties.getValidation().isEnabled()).isFalse();
    }

    @Test
    void testAllExecutionModes() {
        SagaEngineProperties.ContextProperties context = properties.getContext();
        
        // Test all execution modes can be set
        for (SagaContextFactory.ExecutionMode mode : SagaContextFactory.ExecutionMode.values()) {
            context.setExecutionMode(mode);
            assertThat(context.getExecutionMode()).isEqualTo(mode);
        }
    }

    @Test
    void testAllCompensationPolicies() {
        // Test all compensation policies can be set
        for (SagaEngine.CompensationPolicy policy : SagaEngine.CompensationPolicy.values()) {
            properties.setCompensationPolicy(policy);
            assertThat(properties.getCompensationPolicy()).isEqualTo(policy);
        }
    }

    @Test
    void testPropertiesImmutabilityAfterCreation() {
        // Verify that getting nested properties returns the same instance
        SagaEngineProperties.ContextProperties context1 = properties.getContext();
        SagaEngineProperties.ContextProperties context2 = properties.getContext();
        assertThat(context1).isSameAs(context2);

        SagaEngineProperties.BackpressureProperties backpressure1 = properties.getBackpressure();
        SagaEngineProperties.BackpressureProperties backpressure2 = properties.getBackpressure();
        assertThat(backpressure1).isSameAs(backpressure2);

        SagaEngineProperties.CompensationProperties compensation1 = properties.getCompensation();
        SagaEngineProperties.CompensationProperties compensation2 = properties.getCompensation();
        assertThat(compensation1).isSameAs(compensation2);

        SagaEngineProperties.ObservabilityProperties observability1 = properties.getObservability();
        SagaEngineProperties.ObservabilityProperties observability2 = properties.getObservability();
        assertThat(observability1).isSameAs(observability2);

        SagaEngineProperties.ValidationProperties validation1 = properties.getValidation();
        SagaEngineProperties.ValidationProperties validation2 = properties.getValidation();
        assertThat(validation1).isSameAs(validation2);
    }

    @Test
    void testDurationProperties() {
        // Test various duration values
        Duration shortDuration = Duration.ofMillis(100);
        Duration mediumDuration = Duration.ofSeconds(30);
        Duration longDuration = Duration.ofMinutes(10);

        properties.setDefaultTimeout(longDuration);
        properties.getBackpressure().setTimeout(mediumDuration);
        properties.getCompensation().setRetryDelay(shortDuration);
        properties.getObservability().setMetricsInterval(mediumDuration);

        assertThat(properties.getDefaultTimeout()).isEqualTo(longDuration);
        assertThat(properties.getBackpressure().getTimeout()).isEqualTo(mediumDuration);
        assertThat(properties.getCompensation().getRetryDelay()).isEqualTo(shortDuration);
        assertThat(properties.getObservability().getMetricsInterval()).isEqualTo(mediumDuration);
    }
}
