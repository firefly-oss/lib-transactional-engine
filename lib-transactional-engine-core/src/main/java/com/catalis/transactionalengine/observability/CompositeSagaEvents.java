package com.catalis.transactionalengine.observability;

import com.catalis.transactionalengine.core.SagaContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Fan-out implementation of SagaEvents that delegates to multiple sinks
 * (e.g., logs + metrics + tracing). Used by default configuration to
 * avoid bean conflicts while enabling multiple observability channels.
 */
public class CompositeSagaEvents implements SagaEvents {
    private final List<SagaEvents> delegates;

    public CompositeSagaEvents(Collection<SagaEvents> delegates) {
        this.delegates = new ArrayList<>(Objects.requireNonNull(delegates, "delegates"));
    }

    @Override
    public void onStart(String sagaName, String sagaId) {
        for (SagaEvents d : delegates) d.onStart(sagaName, sagaId);
    }

    @Override
    public void onStart(String sagaName, String sagaId, SagaContext ctx) {
        for (SagaEvents d : delegates) d.onStart(sagaName, sagaId, ctx);
    }

    @Override
    public void onStepStarted(String sagaName, String sagaId, String stepId) {
        for (SagaEvents d : delegates) d.onStepStarted(sagaName, sagaId, stepId);
    }

    @Override
    public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {
        for (SagaEvents d : delegates) d.onStepSuccess(sagaName, sagaId, stepId, attempts, latencyMs);
    }

    @Override
    public void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) {
        for (SagaEvents d : delegates) d.onStepFailed(sagaName, sagaId, stepId, error, attempts, latencyMs);
    }

    @Override
    public void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) {
        for (SagaEvents d : delegates) d.onCompensated(sagaName, sagaId, stepId, error);
    }

    @Override
    public void onStepSkippedIdempotent(String sagaName, String sagaId, String stepId) {
        for (SagaEvents d : delegates) d.onStepSkippedIdempotent(sagaName, sagaId, stepId);
    }

    @Override
    public void onCompensationStarted(String sagaName, String sagaId, String stepId) {
        for (SagaEvents d : delegates) d.onCompensationStarted(sagaName, sagaId, stepId);
    }

    @Override
    public void onCompensationRetry(String sagaName, String sagaId, String stepId, int attempt) {
        for (SagaEvents d : delegates) d.onCompensationRetry(sagaName, sagaId, stepId, attempt);
    }

    @Override
    public void onCompensationSkipped(String sagaName, String sagaId, String stepId, String reason) {
        for (SagaEvents d : delegates) d.onCompensationSkipped(sagaName, sagaId, stepId, reason);
    }

    @Override
    public void onCompensationCircuitOpen(String sagaName, String sagaId, String stepId) {
        for (SagaEvents d : delegates) d.onCompensationCircuitOpen(sagaName, sagaId, stepId);
    }

    @Override
    public void onCompensationBatchCompleted(String sagaName, String sagaId, List<String> stepIds, boolean allSuccessful) {
        for (SagaEvents d : delegates) d.onCompensationBatchCompleted(sagaName, sagaId, stepIds, allSuccessful);
    }

    @Override
    public void onCompleted(String sagaName, String sagaId, boolean success) {
        for (SagaEvents d : delegates) d.onCompleted(sagaName, sagaId, success);
    }
}
