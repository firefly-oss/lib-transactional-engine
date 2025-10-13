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


package com.firefly.transactional.saga.observability;


import com.firefly.transactional.saga.composition.SagaCompositionContext;
import com.firefly.transactional.saga.core.SagaContext;

import java.util.List;

/**
 * Observability hook for Saga lifecycle events.
 * Provide your own Spring bean of this type to export metrics/traces/logs.
 * A default logger-based implementation is provided: {@link SagaLoggerEvents}.
 *
 * Notes:
 * - onCompensated is invoked for both success and error cases; a null error indicates a successful compensation.
 */
public interface SagaEvents {
    /** Invoked when a saga starts. */
    default void onStart(String sagaName, String sagaId) {}

    /** Invoked when a saga starts with context. */
    default void onStart(String sagaName, String sagaId, com.firefly.transactional.saga.core.SagaContext ctx) {}

    /** Invoked when a saga starts. */
    default void onStarted(String sagaName, String sagaId) {}

    /** Invoked when a step transitions to RUNNING. */
    default void onStepStarted(String sagaName, String sagaId, String stepId) {}

    default void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {}

    default void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) {}
    default void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) {}
    default void onStepSkippedIdempotent(String sagaName, String sagaId, String stepId) {}

    // New compensation-specific events
    default void onCompensationStarted(String sagaName, String sagaId, String stepId) {}
    default void onCompensationRetry(String sagaName, String sagaId, String stepId, int attempt) {}
    default void onCompensationSkipped(String sagaName, String sagaId, String stepId, String reason) {}
    default void onCompensationCircuitOpen(String sagaName, String sagaId, String stepId) {}
    default void onCompensationBatchCompleted(String sagaName, String sagaId, List<String> stepIds, boolean allSuccessful) {}

    default void onCompleted(String sagaName, String sagaId, boolean success) {}

    // Composition-level events for saga compositions
    /** Invoked when a saga composition starts execution. */
    default void onCompositionStarted(String compositionName, String compositionId) {}
    /** Optional: access to composition context on start for header propagation/tracing injection. */
    default void onCompositionStarted(String compositionName, String compositionId, SagaCompositionContext ctx) {}
    /** Invoked when a saga within a composition starts execution. */
    default void onCompositionSagaStarted(String compositionName, String compositionId, String sagaId, String sagaName) {}
    /** Invoked when a saga within a composition completes successfully. */
    default void onCompositionSagaCompleted(String compositionName, String compositionId, String sagaId, String sagaName, long latencyMs) {}
    /** Invoked when a saga within a composition fails. */
    default void onCompositionSagaFailed(String compositionName, String compositionId, String sagaId, String sagaName, Throwable error, long latencyMs) {}
    /** Invoked when a saga within a composition is skipped due to execution conditions. */
    default void onCompositionSagaSkipped(String compositionName, String compositionId, String sagaId, String sagaName, String reason) {}
    /** Invoked when a composition completes (successfully or with failures). */
    default void onCompositionCompleted(String compositionName, String compositionId, boolean success, long latencyMs, int completedSagas, int failedSagas, int skippedSagas) {}
    /** Invoked when a composition fails with a composition-level error. */
    default void onCompositionFailed(String compositionName, String compositionId, Throwable error, long latencyMs) {}
    /** Invoked when composition compensation starts. */
    default void onCompositionCompensationStarted(String compositionName, String compositionId) {}
    /** Invoked when composition compensation completes. */
    default void onCompositionCompensationCompleted(String compositionName, String compositionId, boolean success) {}
}
