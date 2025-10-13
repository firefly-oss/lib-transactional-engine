/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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

import java.util.List;

/**
 * Observability hook for TCC lifecycle events.
 * Provide your own Spring bean of this type to export metrics/traces/logs.
 * A default logger-based implementation is provided: {@link TccLoggerEvents}.
 *
 * <p>This interface provides hooks for all phases of TCC transaction execution:
 * <ul>
 *   <li>Transaction lifecycle (start, completion)</li>
 *   <li>Phase transitions (try, confirm, cancel)</li>
 *   <li>Participant execution (success, failure)</li>
 * </ul>
 */
public interface TccEvents {
    
    /**
     * Called when a TCC transaction starts.
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     */
    default void onTccStarted(String tccName, String correlationId) {
        // Default implementation does nothing
    }
    
    /**
     * Called when a TCC transaction starts with access to context.
     * Useful for header propagation and tracing injection.
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     * @param context the TCC context
     */
    default void onTccStarted(String tccName, String correlationId, TccContext context) {
        // Default implementation does nothing
    }
    
    /**
     * Called when a TCC transaction completes (successfully or with failure).
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     * @param finalPhase the final phase reached (CONFIRM or CANCEL)
     * @param durationMs the total duration in milliseconds
     */
    default void onTccCompleted(String tccName, String correlationId, TccPhase finalPhase, long durationMs) {}

    /**
     * Called when a TCC transaction completes (successfully or with failure).
     * Overloaded version with explicit success flag.
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     * @param success whether the transaction completed successfully
     * @param finalPhase the final phase reached (CONFIRM or CANCEL)
     * @param durationMs the total duration in milliseconds
     */
    default void onTccCompleted(String tccName, String correlationId, boolean success, TccPhase finalPhase, long durationMs) {}

    /**
     * Called when a TCC phase starts (TRY, CONFIRM, or CANCEL).
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     * @param phase the phase that is starting
     */
    default void onPhaseStarted(String tccName, String correlationId, TccPhase phase) {}

    /**
     * Called when a TCC phase completes successfully.
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     * @param phase the phase that completed
     * @param durationMs the phase duration in milliseconds
     */
    default void onPhaseCompleted(String tccName, String correlationId, TccPhase phase, long durationMs) {}

    /**
     * Called when a TCC phase fails.
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     * @param phase the phase that failed
     * @param error the error that occurred
     * @param attempts the number of attempts made
     * @param durationMs the phase duration in milliseconds
     */
    default void onPhaseFailed(String tccName, String correlationId, TccPhase phase,
                              Throwable error, int attempts, long durationMs) {}

    /**
     * Called when a TCC phase fails.
     * Overloaded version without attempts parameter.
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     * @param phase the phase that failed
     * @param error the error that occurred
     * @param durationMs the phase duration in milliseconds
     */
    default void onPhaseFailed(String tccName, String correlationId, TccPhase phase,
                              Throwable error, long durationMs) {}

    /**
     * Called when a participant method starts execution.
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     * @param participantId the participant identifier
     * @param phase the current phase (TRY, CONFIRM, or CANCEL)
     */
    default void onParticipantStarted(String tccName, String correlationId, String participantId, TccPhase phase) {}

    /**
     * Called when a participant method completes successfully.
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     * @param participantId the participant identifier
     * @param phase the current phase (TRY, CONFIRM, or CANCEL)
     * @param attempts the number of attempts made
     * @param durationMs the execution duration in milliseconds
     */
    default void onParticipantSuccess(String tccName, String correlationId, String participantId,
                                     TccPhase phase, int attempts, long durationMs) {}

    /**
     * Called when a participant method fails.
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     * @param participantId the participant identifier
     * @param phase the current phase (TRY, CONFIRM, or CANCEL)
     * @param error the error that occurred
     * @param attempts the number of attempts made
     * @param durationMs the execution duration in milliseconds
     */
    default void onParticipantFailed(String tccName, String correlationId, String participantId,
                                    TccPhase phase, Throwable error, int attempts, long durationMs) {}

    /**
     * Called when cancellation starts for a participant.
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     * @param participantId the participant identifier
     */
    default void onCancellationStarted(String tccName, String correlationId, String participantId) {}

    /**
     * Called when cancellation completes successfully for a participant.
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     * @param participantId the participant identifier
     * @param attempts the number of attempts made
     * @param durationMs the execution duration in milliseconds
     */
    default void onCancellationSuccess(String tccName, String correlationId, String participantId,
                                      int attempts, long durationMs) {}

    /**
     * Called when cancellation fails for a participant.
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     * @param participantId the participant identifier
     * @param error the error that occurred
     * @param attempts the number of attempts made
     * @param durationMs the execution duration in milliseconds
     */
    default void onCancellationFailed(String tccName, String correlationId, String participantId,
                                     Throwable error, int attempts, long durationMs) {}

    /**
     * Called when a participant is retried due to failure.
     *
     * @param tccName the name of the TCC transaction
     * @param correlationId the correlation ID for this execution
     * @param participantId the participant identifier
     * @param phase the current phase (TRY, CONFIRM, or CANCEL)
     * @param attempt the retry attempt number
     * @param error the error that caused the retry
     */
    default void onParticipantRetry(String tccName, String correlationId, String participantId,
                                   TccPhase phase, int attempt, Throwable error) {}

    // TCC-specific events that don't map to generic events
    default void onParticipantRegistered(String tccName, String correlationId, String participantId) {}
    default void onParticipantTimeout(String tccName, String correlationId, String participantId, TccPhase phase) {}
    default void onResourceReserved(String tccName, String correlationId, String participantId, String resourceId) {}
    default void onResourceReleased(String tccName, String correlationId, String participantId, String resourceId) {}
    default void onBatchOperationStarted(String tccName, String correlationId, TccPhase phase, List<String> participantIds) {}
    default void onBatchOperationCompleted(String tccName, String correlationId, TccPhase phase, List<String> participantIds, boolean allSuccessful) {}
}
