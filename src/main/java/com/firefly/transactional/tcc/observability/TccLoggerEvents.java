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

package com.firefly.transactional.tcc.observability;

import com.firefly.transactional.tcc.core.TccContext;
import com.firefly.transactional.tcc.core.TccPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default logger-based implementation of TccEvents.
 * <p>
 * This implementation logs all TCC lifecycle events in a structured JSON format
 * to facilitate monitoring and debugging. The log format is designed to be
 * easily parseable by log aggregation systems.
 * <p>
 * Log levels used:
 * <ul>
 *   <li>INFO - Normal lifecycle events (started, completed, phase transitions)</li>
 *   <li>WARN - Retry events and recoverable failures</li>
 *   <li>ERROR - Failures and errors</li>
 * </ul>
 */
@Component
public class TccLoggerEvents implements TccEvents {

    private static final Logger log = LoggerFactory.getLogger(TccLoggerEvents.class);

    @Override
    public void onTccStarted(String tccName, String correlationId) {
        log.info("{{\"tcc_event\":\"started\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\"}}",
                tccName, correlationId);
    }

    @Override
    public void onTccStarted(String tccName, String correlationId, TccContext context) {
        log.info("{{\"tcc_event\":\"started_with_context\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"headers_count\":\"{}\",\"variables_count\":\"{}\"}}",
                tccName, correlationId, context.getHeaders().size(), context.getVariables().size());
    }

    @Override
    public void onTccCompleted(String tccName, String correlationId, TccPhase finalPhase, long durationMs) {
        log.info("{{\"tcc_event\":\"completed\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"final_phase\":\"{}\",\"duration_ms\":\"{}\"}}",
                tccName, correlationId, finalPhase, durationMs);
    }

    @Override
    public void onTccCompleted(String tccName, String correlationId, boolean success, TccPhase finalPhase, long durationMs) {
        log.info("{{\"tcc_event\":\"completed\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"success\":\"{}\",\"final_phase\":\"{}\",\"duration_ms\":\"{}\"}}",
                tccName, correlationId, success, finalPhase, durationMs);
    }

    @Override
    public void onPhaseStarted(String tccName, String correlationId, TccPhase phase) {
        log.info("{{\"tcc_event\":\"phase_started\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"phase\":\"{}\"}}",
                tccName, correlationId, phase);
    }

    @Override
    public void onPhaseCompleted(String tccName, String correlationId, TccPhase phase, long durationMs) {
        log.info("{{\"tcc_event\":\"phase_completed\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"phase\":\"{}\",\"duration_ms\":\"{}\"}}",
                tccName, correlationId, phase, durationMs);
    }

    @Override
    public void onPhaseFailed(String tccName, String correlationId, TccPhase phase, Throwable error, int attempts, long durationMs) {
        log.error("{{\"tcc_event\":\"phase_failed\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"phase\":\"{}\",\"error_class\":\"{}\",\"error_message\":\"{}\",\"attempts\":\"{}\",\"duration_ms\":\"{}\"}}",
                tccName, correlationId, phase, error.getClass().getSimpleName(), error.getMessage(), attempts, durationMs);
    }

    @Override
    public void onPhaseFailed(String tccName, String correlationId, TccPhase phase, Throwable error, long durationMs) {
        log.error("{{\"tcc_event\":\"phase_failed\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"phase\":\"{}\",\"error_class\":\"{}\",\"error_message\":\"{}\",\"duration_ms\":\"{}\"}}",
                tccName, correlationId, phase, error.getClass().getSimpleName(), error.getMessage(), durationMs);
    }

    @Override
    public void onParticipantStarted(String tccName, String correlationId, String participantId, TccPhase phase) {
        log.info("{{\"tcc_event\":\"participant_started\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"participant\":\"{}\",\"phase\":\"{}\"}}",
                tccName, correlationId, participantId, phase);
    }

    @Override
    public void onParticipantSuccess(String tccName, String correlationId, String participantId, TccPhase phase, int attempts, long durationMs) {
        log.info("{{\"tcc_event\":\"participant_success\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"participant\":\"{}\",\"phase\":\"{}\",\"attempts\":\"{}\",\"duration_ms\":\"{}\"}}",
                tccName, correlationId, participantId, phase, attempts, durationMs);
    }

    @Override
    public void onParticipantFailed(String tccName, String correlationId, String participantId, TccPhase phase, Throwable error, int attempts, long durationMs) {
        log.error("{{\"tcc_event\":\"participant_failed\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"participant\":\"{}\",\"phase\":\"{}\",\"error_class\":\"{}\",\"error_message\":\"{}\",\"attempts\":\"{}\",\"duration_ms\":\"{}\"}}",
                tccName, correlationId, participantId, phase, error.getClass().getSimpleName(), error.getMessage(), attempts, durationMs);
    }

    @Override
    public void onParticipantRetry(String tccName, String correlationId, String participantId, TccPhase phase, int attempt, Throwable error) {
        log.warn("{{\"tcc_event\":\"participant_retry\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"participant\":\"{}\",\"phase\":\"{}\",\"attempt\":\"{}\",\"error_class\":\"{}\",\"error_message\":\"{}\"}}",
                tccName, correlationId, participantId, phase, attempt, error.getClass().getSimpleName(), error.getMessage());
    }

    @Override
    public void onCancellationStarted(String tccName, String correlationId, String participantId) {
        log.info("{{\"tcc_event\":\"cancellation_started\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"participant\":\"{}\"}}",
                tccName, correlationId, participantId);
    }

    @Override
    public void onCancellationSuccess(String tccName, String correlationId, String participantId, int attempts, long durationMs) {
        log.info("{{\"tcc_event\":\"cancellation_success\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"participant\":\"{}\",\"attempts\":\"{}\",\"duration_ms\":\"{}\"}}",
                tccName, correlationId, participantId, attempts, durationMs);
    }

    @Override
    public void onCancellationFailed(String tccName, String correlationId, String participantId, Throwable error, int attempts, long durationMs) {
        log.error("{{\"tcc_event\":\"cancellation_failed\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"participant\":\"{}\",\"error_class\":\"{}\",\"error_message\":\"{}\",\"attempts\":\"{}\",\"duration_ms\":\"{}\"}}",
                tccName, correlationId, participantId, error.getClass().getSimpleName(), error.getMessage(), attempts, durationMs);
    }

    @Override
    public void onParticipantRegistered(String tccName, String correlationId, String participantId) {
        log.info("{{\"tcc_event\":\"participant_registered\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"participant\":\"{}\"}}",
                tccName, correlationId, participantId);
    }

    @Override
    public void onParticipantTimeout(String tccName, String correlationId, String participantId, TccPhase phase) {
        log.warn("{{\"tcc_event\":\"participant_timeout\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"participant\":\"{}\",\"phase\":\"{}\"}}",
                tccName, correlationId, participantId, phase);
    }

    @Override
    public void onResourceReserved(String tccName, String correlationId, String participantId, String resourceId) {
        log.info("{{\"tcc_event\":\"resource_reserved\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"participant\":\"{}\",\"resource_id\":\"{}\"}}",
                tccName, correlationId, participantId, resourceId);
    }

    @Override
    public void onResourceReleased(String tccName, String correlationId, String participantId, String resourceId) {
        log.info("{{\"tcc_event\":\"resource_released\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"participant\":\"{}\",\"resource_id\":\"{}\"}}",
                tccName, correlationId, participantId, resourceId);
    }

    @Override
    public void onBatchOperationStarted(String tccName, String correlationId, TccPhase phase, List<String> participantIds) {
        log.info("{{\"tcc_event\":\"batch_operation_started\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"phase\":\"{}\",\"participant_count\":\"{}\"}}",
                tccName, correlationId, phase, participantIds.size());
    }

    @Override
    public void onBatchOperationCompleted(String tccName, String correlationId, TccPhase phase, List<String> participantIds, boolean allSuccessful) {
        log.info("{{\"tcc_event\":\"batch_operation_completed\",\"tcc_name\":\"{}\",\"correlation_id\":\"{}\",\"phase\":\"{}\",\"participant_count\":\"{}\",\"all_successful\":\"{}\"}}",
                tccName, correlationId, phase, participantIds.size(), allSuccessful);
    }
}
