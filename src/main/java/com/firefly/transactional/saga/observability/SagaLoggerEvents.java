/*
 * Copyright 2024 Firefly Authors
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

import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.saga.composition.SagaCompositionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default logger-based implementation of SagaEvents.
 * <p>
 * This implementation logs all SAGA lifecycle events in a structured JSON format
 * to facilitate monitoring and debugging. The log format is designed to be
 * easily parseable by log aggregation systems.
 * <p>
 * Log levels used:
 * <ul>
 *   <li>INFO - Normal lifecycle events (started, completed, step transitions)</li>
 *   <li>WARN - Retry events and recoverable failures</li>
 *   <li>ERROR - Failures and errors</li>
 * </ul>
 */
@Component
public class SagaLoggerEvents implements SagaEvents {

    private static final Logger log = LoggerFactory.getLogger(SagaLoggerEvents.class);

    @Override
    public void onStart(String sagaName, String sagaId) {
        log.info("{{\"saga_event\":\"started\",\"saga_name\":\"{}\",\"saga_id\":\"{}\"}}",
                sagaName, sagaId);
    }

    @Override
    public void onStart(String sagaName, String sagaId, SagaContext ctx) {
        log.info("{{\"saga_event\":\"started_with_context\",\"saga_name\":\"{}\",\"saga_id\":\"{}\",\"headers_count\":\"{}\",\"variables_count\":\"{}\"}}",
                sagaName, sagaId, ctx.headers().size(), ctx.variables().size());
    }

    @Override
    public void onStarted(String sagaName, String sagaId) {
        log.info("{{\"saga_event\":\"started_confirmed\",\"saga_name\":\"{}\",\"saga_id\":\"{}\"}}",
                sagaName, sagaId);
    }

    @Override
    public void onStepStarted(String sagaName, String sagaId, String stepId) {
        log.info("{{\"saga_event\":\"step_started\",\"saga_name\":\"{}\",\"saga_id\":\"{}\",\"step_id\":\"{}\"}}",
                sagaName, sagaId, stepId);
    }

    @Override
    public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {
        log.info("{{\"saga_event\":\"step_success\",\"saga_name\":\"{}\",\"saga_id\":\"{}\",\"step_id\":\"{}\",\"attempts\":\"{}\",\"latency_ms\":\"{}\"}}",
                sagaName, sagaId, stepId, attempts, latencyMs);
    }

    @Override
    public void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) {
        log.error("{{\"saga_event\":\"step_failed\",\"saga_name\":\"{}\",\"saga_id\":\"{}\",\"step_id\":\"{}\",\"error_class\":\"{}\",\"error_message\":\"{}\",\"attempts\":\"{}\",\"latency_ms\":\"{}\"}}",
                sagaName, sagaId, stepId, error.getClass().getSimpleName(), error.getMessage(), attempts, latencyMs);
    }

    @Override
    public void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) {
        if (error == null) {
            log.info("{{\"saga_event\":\"compensated_success\",\"saga_name\":\"{}\",\"saga_id\":\"{}\",\"step_id\":\"{}\"}}",
                    sagaName, sagaId, stepId);
        } else {
            log.error("{{\"saga_event\":\"compensated_failed\",\"saga_name\":\"{}\",\"saga_id\":\"{}\",\"step_id\":\"{}\",\"error_class\":\"{}\",\"error_message\":\"{}\"}}",
                    sagaName, sagaId, stepId, error.getClass().getSimpleName(), error.getMessage());
        }
    }

    @Override
    public void onStepSkippedIdempotent(String sagaName, String sagaId, String stepId) {
        log.info("{{\"saga_event\":\"step_skipped_idempotent\",\"saga_name\":\"{}\",\"saga_id\":\"{}\",\"step_id\":\"{}\"}}",
                sagaName, sagaId, stepId);
    }

    @Override
    public void onCompensationStarted(String sagaName, String sagaId, String stepId) {
        log.info("{{\"saga_event\":\"compensation_started\",\"saga_name\":\"{}\",\"saga_id\":\"{}\",\"step_id\":\"{}\"}}",
                sagaName, sagaId, stepId);
    }

    @Override
    public void onCompensationRetry(String sagaName, String sagaId, String stepId, int attempt) {
        log.warn("{{\"saga_event\":\"compensation_retry\",\"saga_name\":\"{}\",\"saga_id\":\"{}\",\"step_id\":\"{}\",\"attempt\":\"{}\"}}",
                sagaName, sagaId, stepId, attempt);
    }

    @Override
    public void onCompensationSkipped(String sagaName, String sagaId, String stepId, String reason) {
        log.info("{{\"saga_event\":\"compensation_skipped\",\"saga_name\":\"{}\",\"saga_id\":\"{}\",\"step_id\":\"{}\",\"reason\":\"{}\"}}",
                sagaName, sagaId, stepId, reason);
    }

    @Override
    public void onCompensationCircuitOpen(String sagaName, String sagaId, String stepId) {
        log.warn("{{\"saga_event\":\"compensation_circuit_open\",\"saga_name\":\"{}\",\"saga_id\":\"{}\",\"step_id\":\"{}\"}}",
                sagaName, sagaId, stepId);
    }

    @Override
    public void onCompensationBatchCompleted(String sagaName, String sagaId, List<String> stepIds, boolean allSuccessful) {
        log.info("{{\"saga_event\":\"compensation_batch_completed\",\"saga_name\":\"{}\",\"saga_id\":\"{}\",\"step_count\":\"{}\",\"all_successful\":\"{}\"}}",
                sagaName, sagaId, stepIds.size(), allSuccessful);
    }

    @Override
    public void onCompleted(String sagaName, String sagaId, boolean success) {
        log.info("{{\"saga_event\":\"completed\",\"saga_name\":\"{}\",\"saga_id\":\"{}\",\"success\":\"{}\"}}",
                sagaName, sagaId, success);
    }

    @Override
    public void onCompositionStarted(String compositionName, String compositionId) {
        log.info("{{\"saga_event\":\"composition_started\",\"composition_name\":\"{}\",\"composition_id\":\"{}\"}}",
                compositionName, compositionId);
    }

    @Override
    public void onCompositionStarted(String compositionName, String compositionId, SagaCompositionContext ctx) {
        log.info("{{\"saga_event\":\"composition_started_with_context\",\"composition_name\":\"{}\",\"composition_id\":\"{}\",\"shared_variables_count\":\"{}\",\"completed_sagas_count\":\"{}\"}}",
                compositionName, compositionId, ctx.getSharedVariables().size(), ctx.getCompletedSagas().size());
    }

    @Override
    public void onCompositionSagaStarted(String compositionName, String compositionId, String sagaId, String sagaName) {
        log.info("{{\"saga_event\":\"composition_saga_started\",\"composition_name\":\"{}\",\"composition_id\":\"{}\",\"saga_id\":\"{}\",\"saga_name\":\"{}\"}}",
                compositionName, compositionId, sagaId, sagaName);
    }

    @Override
    public void onCompositionSagaCompleted(String compositionName, String compositionId, String sagaId, String sagaName, long latencyMs) {
        log.info("{{\"saga_event\":\"composition_saga_completed\",\"composition_name\":\"{}\",\"composition_id\":\"{}\",\"saga_id\":\"{}\",\"saga_name\":\"{}\",\"latency_ms\":\"{}\"}}",
                compositionName, compositionId, sagaId, sagaName, latencyMs);
    }

    @Override
    public void onCompositionSagaFailed(String compositionName, String compositionId, String sagaId, String sagaName, Throwable error, long latencyMs) {
        log.error("{{\"saga_event\":\"composition_saga_failed\",\"composition_name\":\"{}\",\"composition_id\":\"{}\",\"saga_id\":\"{}\",\"saga_name\":\"{}\",\"error_class\":\"{}\",\"error_message\":\"{}\",\"latency_ms\":\"{}\"}}",
                compositionName, compositionId, sagaId, sagaName, error.getClass().getSimpleName(), error.getMessage(), latencyMs);
    }

    @Override
    public void onCompositionSagaSkipped(String compositionName, String compositionId, String sagaId, String sagaName, String reason) {
        log.info("{{\"saga_event\":\"composition_saga_skipped\",\"composition_name\":\"{}\",\"composition_id\":\"{}\",\"saga_id\":\"{}\",\"saga_name\":\"{}\",\"reason\":\"{}\"}}",
                compositionName, compositionId, sagaId, sagaName, reason);
    }

    @Override
    public void onCompositionCompleted(String compositionName, String compositionId, boolean success, long latencyMs, int completedSagas, int failedSagas, int skippedSagas) {
        log.info("{{\"saga_event\":\"composition_completed\",\"composition_name\":\"{}\",\"composition_id\":\"{}\",\"success\":\"{}\",\"latency_ms\":\"{}\",\"completed_sagas\":\"{}\",\"failed_sagas\":\"{}\",\"skipped_sagas\":\"{}\"}}",
                compositionName, compositionId, success, latencyMs, completedSagas, failedSagas, skippedSagas);
    }

    @Override
    public void onCompositionFailed(String compositionName, String compositionId, Throwable error, long latencyMs) {
        log.error("{{\"saga_event\":\"composition_failed\",\"composition_name\":\"{}\",\"composition_id\":\"{}\",\"error_class\":\"{}\",\"error_message\":\"{}\",\"latency_ms\":\"{}\"}}",
                compositionName, compositionId, error.getClass().getSimpleName(), error.getMessage(), latencyMs);
    }

    @Override
    public void onCompositionCompensationStarted(String compositionName, String compositionId) {
        log.info("{{\"saga_event\":\"composition_compensation_started\",\"composition_name\":\"{}\",\"composition_id\":\"{}\"}}",
                compositionName, compositionId);
    }

    @Override
    public void onCompositionCompensationCompleted(String compositionName, String compositionId, boolean success) {
        log.info("{{\"saga_event\":\"composition_compensation_completed\",\"composition_name\":\"{}\",\"composition_id\":\"{}\",\"success\":\"{}\"}}",
                compositionName, compositionId, success);
    }
}
