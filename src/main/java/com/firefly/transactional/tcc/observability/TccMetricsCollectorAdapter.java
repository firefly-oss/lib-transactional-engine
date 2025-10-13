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

package com.firefly.transactional.tcc.observability;

import com.firefly.transactional.shared.observability.TransactionalMetricsCollector;
import com.firefly.transactional.tcc.core.TccPhase;
import org.springframework.stereotype.Component;

/**
 * Adapter that provides TCC-specific metrics collection interface
 * while delegating to the shared TransactionalMetricsCollector.
 * <p>
 * This maintains backward compatibility with existing TCC code
 * while using the unified metrics collection system.
 */
@Component
public class TccMetricsCollectorAdapter {

    private static final String TRANSACTION_TYPE = "TCC";
    private final TransactionalMetricsCollector metricsCollector;

    public TccMetricsCollectorAdapter(TransactionalMetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * Records that a TCC transaction has started.
     */
    public void recordTccStarted(String tccName, String correlationId) {
        metricsCollector.recordTransactionStarted(TRANSACTION_TYPE, tccName, correlationId);
    }

    /**
     * Records that a TCC transaction has completed.
     */
    public void recordTccCompleted(String tccName, String correlationId, TccPhase finalPhase, long durationMs) {
        boolean success = finalPhase == TccPhase.CONFIRM;
        metricsCollector.recordTransactionCompleted(TRANSACTION_TYPE, tccName, correlationId, success, durationMs);
    }

    /**
     * Records that a TCC transaction has completed successfully.
     */
    public void recordTccCompleted(String tccName, String correlationId) {
        metricsCollector.recordTransactionCompleted(TRANSACTION_TYPE, tccName, correlationId, true, 0L);
    }

    /**
     * Records that a TCC transaction has failed.
     */
    public void recordTccFailed(String tccName, String correlationId, long durationMs) {
        metricsCollector.recordTransactionCompleted(TRANSACTION_TYPE, tccName, correlationId, false, durationMs);
    }

    /**
     * Records that a TCC transaction has failed.
     */
    public void recordTccFailed(String tccName, String correlationId) {
        metricsCollector.recordTransactionCompleted(TRANSACTION_TYPE, tccName, correlationId, false, 0L);
    }

    /**
     * Records that a TCC phase has started.
     */
    public void recordPhaseStarted(String tccName, TccPhase phase) {
        String stepId = phase.name().toLowerCase();
        metricsCollector.recordStepStarted(TRANSACTION_TYPE, tccName, stepId);
    }

    /**
     * Records that a TCC phase has completed successfully.
     */
    public void recordPhaseCompleted(String tccName, TccPhase phase, int attempts) {
        String stepId = phase.name().toLowerCase();
        metricsCollector.recordStepCompleted(TRANSACTION_TYPE, tccName, stepId, attempts);
    }

    /**
     * Records that a TCC phase has completed successfully.
     */
    public void recordPhaseCompleted(String tccName, TccPhase phase) {
        String stepId = phase.name().toLowerCase();
        metricsCollector.recordStepCompleted(TRANSACTION_TYPE, tccName, stepId, 1);
    }

    /**
     * Records that a TCC phase has failed.
     */
    public void recordPhaseFailed(String tccName, TccPhase phase, int attempts) {
        String stepId = phase.name().toLowerCase();
        metricsCollector.recordStepFailed(TRANSACTION_TYPE, tccName, stepId, attempts);
    }

    /**
     * Records that a TCC phase has failed.
     */
    public void recordPhaseFailed(String tccName, TccPhase phase) {
        String stepId = phase.name().toLowerCase();
        metricsCollector.recordStepFailed(TRANSACTION_TYPE, tccName, stepId, 1);
    }

    /**
     * Records that a participant operation has started.
     */
    public void recordParticipantStarted(String tccName, String participantId, TccPhase phase) {
        String stepId = participantId + ":" + phase.name().toLowerCase();
        metricsCollector.recordStepStarted(TRANSACTION_TYPE, tccName, stepId);
    }

    /**
     * Records that a participant operation has completed successfully.
     */
    public void recordParticipantCompleted(String tccName, String participantId, TccPhase phase, int attempts) {
        String stepId = participantId + ":" + phase.name().toLowerCase();
        metricsCollector.recordStepCompleted(TRANSACTION_TYPE, tccName, stepId, attempts);
    }

    /**
     * Records that a participant operation has completed successfully.
     */
    public void recordParticipantCompleted(String tccName, String participantId, TccPhase phase) {
        String stepId = participantId + ":" + phase.name().toLowerCase();
        metricsCollector.recordStepCompleted(TRANSACTION_TYPE, tccName, stepId, 1);
    }

    /**
     * Records that a participant operation has failed.
     */
    public void recordParticipantFailed(String tccName, String participantId, TccPhase phase, int attempts) {
        String stepId = participantId + ":" + phase.name().toLowerCase();
        metricsCollector.recordStepFailed(TRANSACTION_TYPE, tccName, stepId, attempts);
    }

    /**
     * Records that a participant operation has failed.
     */
    public void recordParticipantFailed(String tccName, String participantId, TccPhase phase) {
        String stepId = participantId + ":" + phase.name().toLowerCase();
        metricsCollector.recordStepFailed(TRANSACTION_TYPE, tccName, stepId, 1);
    }

    /**
     * Records that cancellation has started.
     */
    public void recordCancellationStarted(String tccName, String participantId) {
        metricsCollector.recordCompensationStarted(TRANSACTION_TYPE, tccName, participantId);
    }

    /**
     * Records that cancellation has completed successfully.
     */
    public void recordCancellationCompleted(String tccName, String participantId) {
        metricsCollector.recordCompensationCompleted(TRANSACTION_TYPE, tccName, participantId, true);
    }

    /**
     * Records that cancellation has failed.
     */
    public void recordCancellationFailed(String tccName, String participantId) {
        metricsCollector.recordCompensationCompleted(TRANSACTION_TYPE, tccName, participantId, false);
    }

    /**
     * Gets TCC-specific metrics.
     */
    public com.firefly.transactional.shared.observability.TransactionMetrics getTccMetrics() {
        return metricsCollector.getMetrics(TRANSACTION_TYPE);
    }

    /**
     * Gets the underlying shared metrics collector.
     */
    public TransactionalMetricsCollector getSharedMetricsCollector() {
        return metricsCollector;
    }
}
