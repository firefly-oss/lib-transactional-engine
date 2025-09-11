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
    default void onStart(String sagaName, String sagaId) {}
    /** Optional: access to context on start for header propagation/tracing injection. */
    default void onStart(String sagaName, String sagaId, com.firefly.transactional.core.SagaContext ctx) {}
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
}
