package com.firefly.transactionalengine.observability;

import com.firefly.transactionalengine.core.SagaContext;

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
    default void onStart(String sagaName, String sagaId, com.firefly.transactionalengine.core.SagaContext ctx) {}
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
