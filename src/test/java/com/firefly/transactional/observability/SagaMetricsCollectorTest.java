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

package com.firefly.transactional.observability;

import com.firefly.transactional.saga.observability.SagaMetricsCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SagaMetricsCollectorTest {

    private SagaMetricsCollector metricsCollector;
    private String sagaName;
    private String correlationId;
    private String stepId;

    @BeforeEach
    void setUp() {
        metricsCollector = new SagaMetricsCollector();
        sagaName = "test-saga";
        correlationId = "test-correlation-id";
        stepId = "test-step";
    }

    @Test
    void testSagaLifecycleMetrics() {
        // Initially, all metrics should be zero
        SagaMetricsCollector.MetricsSnapshot initialSnapshot = metricsCollector.getMetrics();
        assertThat(initialSnapshot.getSagasStarted()).isZero();
        assertThat(initialSnapshot.getSagasCompleted()).isZero();
        assertThat(initialSnapshot.getSagasFailed()).isZero();
        assertThat(initialSnapshot.getSagasCompensated()).isZero();

        // Record saga started
        metricsCollector.recordSagaStarted(sagaName, correlationId);
        SagaMetricsCollector.MetricsSnapshot afterStart = metricsCollector.getMetrics();
        assertThat(afterStart.getSagasStarted()).isEqualTo(1);
        assertThat(afterStart.getActiveSagas()).isEqualTo(1);

        // Record saga completed
        metricsCollector.recordSagaCompleted(sagaName, correlationId, Duration.ofMillis(100));
        SagaMetricsCollector.MetricsSnapshot afterComplete = metricsCollector.getMetrics();
        assertThat(afterComplete.getSagasCompleted()).isEqualTo(1);
        assertThat(afterComplete.getActiveSagas()).isZero();
        assertThat(afterComplete.getAverageExecutionTimeMs()).isEqualTo(100);
        assertThat(afterComplete.getMaxExecutionTimeMs()).isEqualTo(100);
        assertThat(afterComplete.getMinExecutionTimeMs()).isEqualTo(100);
    }

    @Test
    void testSagaFailureMetrics() {
        metricsCollector.recordSagaStarted(sagaName, correlationId);
        metricsCollector.recordSagaFailed(sagaName, correlationId, new RuntimeException("Test error"));

        SagaMetricsCollector.MetricsSnapshot snapshot = metricsCollector.getMetrics();
        assertThat(snapshot.getSagasFailed()).isEqualTo(1);
        assertThat(snapshot.getActiveSagas()).isZero();
        assertThat(snapshot.getSagaSuccessRate()).isZero();
    }

    @Test
    void testSagaCompensationMetrics() {
        metricsCollector.recordSagaStarted(sagaName, correlationId);
        metricsCollector.recordSagaCompensated(sagaName, correlationId);

        SagaMetricsCollector.MetricsSnapshot snapshot = metricsCollector.getMetrics();
        assertThat(snapshot.getSagasCompensated()).isEqualTo(1);
        assertThat(snapshot.getActiveSagas()).isZero();
    }

    @Test
    void testStepLifecycleMetrics() {
        // Record step started
        metricsCollector.recordStepStarted(stepId, sagaName, correlationId);
        SagaMetricsCollector.MetricsSnapshot afterStart = metricsCollector.getMetrics();
        assertThat(afterStart.getStepsExecuted()).isEqualTo(1);
        assertThat(afterStart.getActiveSteps()).isEqualTo(1);

        // Record step succeeded
        metricsCollector.recordStepSucceeded(stepId, sagaName, correlationId);
        SagaMetricsCollector.MetricsSnapshot afterSuccess = metricsCollector.getMetrics();
        assertThat(afterSuccess.getStepsSucceeded()).isEqualTo(1);
        assertThat(afterSuccess.getActiveSteps()).isZero();
        assertThat(afterSuccess.getStepSuccessRate()).isEqualTo(1.0);
    }

    @Test
    void testStepFailureMetrics() {
        metricsCollector.recordStepStarted(stepId, sagaName, correlationId);
        metricsCollector.recordStepFailed(stepId, sagaName, correlationId, new RuntimeException("Step error"));

        SagaMetricsCollector.MetricsSnapshot snapshot = metricsCollector.getMetrics();
        assertThat(snapshot.getStepsFailed()).isEqualTo(1);
        assertThat(snapshot.getActiveSteps()).isZero();
        assertThat(snapshot.getStepSuccessRate()).isZero();
    }

    @Test
    void testStepCompensationMetrics() {
        metricsCollector.recordStepCompensated(stepId, sagaName, correlationId);

        SagaMetricsCollector.MetricsSnapshot snapshot = metricsCollector.getMetrics();
        assertThat(snapshot.getStepsCompensated()).isEqualTo(1);
    }

    @Test
    void testOptimizationMetrics() {
        metricsCollector.recordOptimizedContextCreated(sagaName);
        metricsCollector.recordStandardContextCreated(sagaName + "-2");

        SagaMetricsCollector.MetricsSnapshot snapshot = metricsCollector.getMetrics();
        assertThat(snapshot.getOptimizedContextsCreated()).isEqualTo(1);
        assertThat(snapshot.getStandardContextsCreated()).isEqualTo(1);
        assertThat(snapshot.getOptimizationRate()).isEqualTo(0.5);
    }

    @Test
    void testBackpressureMetrics() {
        metricsCollector.recordBackpressureApplied("batched", 10);
        metricsCollector.recordBackpressureApplied("adaptive", 5);

        SagaMetricsCollector.MetricsSnapshot snapshot = metricsCollector.getMetrics();
        assertThat(snapshot.getBackpressureApplications()).isEqualTo(2);
    }

    @Test
    void testExecutionTimeTracking() {
        String correlationId1 = "corr-1";
        String correlationId2 = "corr-2";

        // Start two sagas
        metricsCollector.recordSagaStarted(sagaName, correlationId1);
        metricsCollector.recordSagaStarted(sagaName, correlationId2);

        // Complete them with different execution times
        metricsCollector.recordSagaCompleted(sagaName, correlationId1, Duration.ofMillis(100));
        metricsCollector.recordSagaCompleted(sagaName, correlationId2, Duration.ofMillis(200));

        SagaMetricsCollector.MetricsSnapshot snapshot = metricsCollector.getMetrics();
        assertThat(snapshot.getAverageExecutionTimeMs()).isEqualTo(150); // (100 + 200) / 2
        assertThat(snapshot.getMaxExecutionTimeMs()).isEqualTo(200);
        assertThat(snapshot.getMinExecutionTimeMs()).isEqualTo(100);
    }

    @Test
    void testSuccessRateCalculations() {
        // Record some successful and failed sagas
        for (int i = 0; i < 7; i++) {
            String corrId = "corr-" + i;
            metricsCollector.recordSagaStarted(sagaName, corrId);
            if (i < 5) {
                metricsCollector.recordSagaCompleted(sagaName, corrId, Duration.ofMillis(100));
            } else {
                metricsCollector.recordSagaFailed(sagaName, corrId, new RuntimeException("Error"));
            }
        }

        // Record some successful and failed steps
        for (int i = 0; i < 10; i++) {
            String stepIdWithIndex = stepId + "-" + i;
            metricsCollector.recordStepStarted(stepIdWithIndex, sagaName, correlationId);
            if (i < 8) {
                metricsCollector.recordStepSucceeded(stepIdWithIndex, sagaName, correlationId);
            } else {
                metricsCollector.recordStepFailed(stepIdWithIndex, sagaName, correlationId, new RuntimeException("Step error"));
            }
        }

        SagaMetricsCollector.MetricsSnapshot snapshot = metricsCollector.getMetrics();
        
        // Saga success rate: 5 successful out of 7 total = 5/7 ≈ 0.714
        assertThat(snapshot.getSagaSuccessRate()).isCloseTo(5.0/7.0, within(0.001));
        
        // Step success rate: 8 successful out of 10 total = 8/10 = 0.8
        assertThat(snapshot.getStepSuccessRate()).isEqualTo(0.8);
    }

    @Test
    void testOptimizationRateCalculation() {
        // Record different types of context creation
        metricsCollector.recordOptimizedContextCreated("saga1");
        metricsCollector.recordOptimizedContextCreated("saga2");
        metricsCollector.recordOptimizedContextCreated("saga3");
        metricsCollector.recordStandardContextCreated("saga4");

        SagaMetricsCollector.MetricsSnapshot snapshot = metricsCollector.getMetrics();
        
        // Optimization rate: 3 optimized out of 4 total = 3/4 = 0.75
        assertThat(snapshot.getOptimizationRate()).isEqualTo(0.75);
    }

    @Test
    void testMetricsReset() {
        // Record some metrics
        metricsCollector.recordSagaStarted(sagaName, correlationId);
        metricsCollector.recordStepStarted(stepId, sagaName, correlationId);
        metricsCollector.recordOptimizedContextCreated(sagaName);
        metricsCollector.recordBackpressureApplied("batched", 5);

        // Verify metrics are recorded
        SagaMetricsCollector.MetricsSnapshot beforeReset = metricsCollector.getMetrics();
        assertThat(beforeReset.getSagasStarted()).isEqualTo(1);
        assertThat(beforeReset.getStepsExecuted()).isEqualTo(1);
        assertThat(beforeReset.getOptimizedContextsCreated()).isEqualTo(1);
        assertThat(beforeReset.getBackpressureApplications()).isEqualTo(1);

        // Reset metrics
        metricsCollector.reset();

        // Verify all metrics are reset
        SagaMetricsCollector.MetricsSnapshot afterReset = metricsCollector.getMetrics();
        assertThat(afterReset.getSagasStarted()).isZero();
        assertThat(afterReset.getSagasCompleted()).isZero();
        assertThat(afterReset.getSagasFailed()).isZero();
        assertThat(afterReset.getSagasCompensated()).isZero();
        assertThat(afterReset.getStepsExecuted()).isZero();
        assertThat(afterReset.getStepsSucceeded()).isZero();
        assertThat(afterReset.getStepsFailed()).isZero();
        assertThat(afterReset.getStepsCompensated()).isZero();
        assertThat(afterReset.getOptimizedContextsCreated()).isZero();
        assertThat(afterReset.getStandardContextsCreated()).isZero();
        assertThat(afterReset.getBackpressureApplications()).isZero();
        assertThat(afterReset.getActiveSagas()).isZero();
        assertThat(afterReset.getActiveSteps()).isZero();
        assertThat(afterReset.getAverageExecutionTimeMs()).isZero();
        assertThat(afterReset.getMaxExecutionTimeMs()).isZero();
        assertThat(afterReset.getMinExecutionTimeMs()).isZero();
    }

    @Test
    void testMetricsSnapshotImmutability() {
        metricsCollector.recordSagaStarted(sagaName, correlationId);
        
        SagaMetricsCollector.MetricsSnapshot snapshot1 = metricsCollector.getMetrics();
        assertThat(snapshot1.getSagasStarted()).isEqualTo(1);

        // Record more metrics
        metricsCollector.recordSagaCompleted(sagaName, correlationId, Duration.ofMillis(100));
        
        // Original snapshot should be unchanged
        assertThat(snapshot1.getSagasStarted()).isEqualTo(1);
        assertThat(snapshot1.getSagasCompleted()).isZero();

        // New snapshot should reflect changes
        SagaMetricsCollector.MetricsSnapshot snapshot2 = metricsCollector.getMetrics();
        assertThat(snapshot2.getSagasStarted()).isEqualTo(1);
        assertThat(snapshot2.getSagasCompleted()).isEqualTo(1);
    }

    @Test
    void testZeroDivisionHandling() {
        // Test success rates when no operations have been performed
        SagaMetricsCollector.MetricsSnapshot emptySnapshot = metricsCollector.getMetrics();
        assertThat(emptySnapshot.getSagaSuccessRate()).isZero();
        assertThat(emptySnapshot.getStepSuccessRate()).isZero();
        assertThat(emptySnapshot.getOptimizationRate()).isZero();
    }
}
