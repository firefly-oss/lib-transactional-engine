/*
 * Copyright (c) 2023 Firefly Authors. All rights reserved.
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

package com.firefly.transactional.saga.observability;

import com.firefly.transactional.shared.observability.TransactionalMetricsCollector;
import org.springframework.stereotype.Component;

/**
 * Adapter that provides SAGA-specific metrics collection interface
 * while delegating to the shared TransactionalMetricsCollector.
 * <p>
 * This maintains backward compatibility with existing SAGA code
 * while using the unified metrics collection system.
 */
@Component
public class SagaMetricsCollectorAdapter {

    private static final String TRANSACTION_TYPE = "SAGA";
    private final TransactionalMetricsCollector metricsCollector;

    public SagaMetricsCollectorAdapter(TransactionalMetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * Records that a saga has started.
     */
    public void recordSagaStarted(String sagaName, String sagaId) {
        metricsCollector.recordTransactionStarted(TRANSACTION_TYPE, sagaName, sagaId);
    }

    /**
     * Records that a saga has completed.
     */
    public void recordSagaCompleted(String sagaName, String sagaId, boolean success, long durationMs) {
        metricsCollector.recordTransactionCompleted(TRANSACTION_TYPE, sagaName, sagaId, success, durationMs);
    }

    /**
     * Records that a saga has completed successfully.
     */
    public void recordSagaCompleted(String sagaName, String sagaId) {
        metricsCollector.recordTransactionCompleted(TRANSACTION_TYPE, sagaName, sagaId, true, 0L);
    }

    /**
     * Records that a saga has failed.
     */
    public void recordSagaFailed(String sagaName, String sagaId, long durationMs) {
        metricsCollector.recordTransactionCompleted(TRANSACTION_TYPE, sagaName, sagaId, false, durationMs);
    }

    /**
     * Records that a saga has failed.
     */
    public void recordSagaFailed(String sagaName, String sagaId) {
        metricsCollector.recordTransactionCompleted(TRANSACTION_TYPE, sagaName, sagaId, false, 0L);
    }

    /**
     * Records that a step has started.
     */
    public void recordStepStarted(String sagaName, String stepId) {
        metricsCollector.recordStepStarted(TRANSACTION_TYPE, sagaName, stepId);
    }

    /**
     * Records that a step has completed successfully.
     */
    public void recordStepCompleted(String sagaName, String stepId, int attempts) {
        metricsCollector.recordStepCompleted(TRANSACTION_TYPE, sagaName, stepId, attempts);
    }

    /**
     * Records that a step has completed successfully.
     */
    public void recordStepCompleted(String sagaName, String stepId) {
        metricsCollector.recordStepCompleted(TRANSACTION_TYPE, sagaName, stepId, 1);
    }

    /**
     * Records that a step has failed.
     */
    public void recordStepFailed(String sagaName, String stepId, int attempts) {
        metricsCollector.recordStepFailed(TRANSACTION_TYPE, sagaName, stepId, attempts);
    }

    /**
     * Records that a step has failed.
     */
    public void recordStepFailed(String sagaName, String stepId) {
        metricsCollector.recordStepFailed(TRANSACTION_TYPE, sagaName, stepId, 1);
    }

    /**
     * Records that compensation has started.
     */
    public void recordCompensationStarted(String sagaName, String stepId) {
        metricsCollector.recordCompensationStarted(TRANSACTION_TYPE, sagaName, stepId);
    }

    /**
     * Records that compensation has completed successfully.
     */
    public void recordCompensationCompleted(String sagaName, String stepId) {
        metricsCollector.recordCompensationCompleted(TRANSACTION_TYPE, sagaName, stepId, true);
    }

    /**
     * Records that compensation has failed.
     */
    public void recordCompensationFailed(String sagaName, String stepId) {
        metricsCollector.recordCompensationCompleted(TRANSACTION_TYPE, sagaName, stepId, false);
    }

    /**
     * Gets SAGA-specific metrics.
     */
    public com.firefly.transactional.shared.observability.TransactionMetrics getSagaMetrics() {
        return metricsCollector.getMetrics(TRANSACTION_TYPE);
    }

    /**
     * Gets the underlying shared metrics collector.
     */
    public TransactionalMetricsCollector getSharedMetricsCollector() {
        return metricsCollector;
    }
}
