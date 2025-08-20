package com.catalis.transactionalengine.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link SagaEvents} implementation that emits JSON-friendly key=value logs via SLF4J.
 */
public class SagaLoggerEvents implements SagaEvents {
    private static final Logger log = LoggerFactory.getLogger(SagaLoggerEvents.class);

    @Override
    public void onStart(String sagaName, String sagaId) {
        log.info("saga_event=start saga={} sagaId={}", sagaName, sagaId);
    }

    @Override
    public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {
        log.info("saga_event=step_success saga={} sagaId={} stepId={} attempts={} latencyMs={}", sagaName, sagaId, stepId, attempts, latencyMs);
    }

    @Override
    public void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) {
        log.warn("saga_event=step_failed saga={} sagaId={} stepId={} attempts={} latencyMs={} error={}", sagaName, sagaId, stepId, attempts, latencyMs, error.toString());
    }

    @Override
    public void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) {
        log.info("saga_event=compensated saga={} sagaId={} stepId={} error={} ", sagaName, sagaId, stepId, error == null ? "" : error.toString());
    }

    @Override
    public void onCompleted(String sagaName, String sagaId, boolean success) {
        log.info("saga_event=completed saga={} sagaId={} success={}", sagaName, sagaId, success);
    }
}
