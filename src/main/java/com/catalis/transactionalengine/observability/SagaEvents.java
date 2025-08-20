package com.catalis.transactionalengine.observability;

import com.catalis.transactionalengine.core.SagaContext;

/**
 * Observability hook for Saga lifecycle events.
 * Provide your own Spring bean of this type to export metrics/traces/logs.
 * A default logger-based implementation is provided: {@link SagaLoggerEvents}.
 */
public interface SagaEvents {
    default void onStart(String sagaName, String sagaId) {}
    default void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {}
    default void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) {}
    default void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) {}
    default void onCompleted(String sagaName, String sagaId, boolean success) {}
}
