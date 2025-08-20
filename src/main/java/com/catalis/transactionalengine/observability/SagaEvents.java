package com.catalis.transactionalengine.observability;

import com.catalis.transactionalengine.core.SagaContext;

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
    default void onStart(String sagaName, String sagaId, com.catalis.transactionalengine.core.SagaContext ctx) {}
    /** Invoked when a step transitions to RUNNING. */
    default void onStepStarted(String sagaName, String sagaId, String stepId) {}
    default void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {}
    default void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) {}
    default void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) {}
    default void onStepSkippedIdempotent(String sagaName, String sagaId, String stepId) {}
    default void onCompleted(String sagaName, String sagaId, boolean success) {}
}
