package com.catalis.transactionalengine.observability;

import com.catalis.transactionalengine.core.SagaContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Micrometer Tracing implementation for SagaEvents.
 * Creates a span for the overall saga and a span per step. Adds traceId to headers for propagation.
 */
public class SagaTracingEvents implements SagaEvents {
    private final Tracer tracer;
    private final Map<String, Span> sagaSpans = new ConcurrentHashMap<>();
    private final Map<String, Span> stepSpans = new ConcurrentHashMap<>(); // key: sagaId:stepId

    public SagaTracingEvents(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void onStart(String sagaName, String sagaId, SagaContext ctx) {
        Span span = tracer.nextSpan().name("saga:" + sagaName).start();
        sagaSpans.put(sagaId, span);
        String traceId = span.context().traceId();
        if (ctx != null && traceId != null) {
            ctx.putHeader("X-Trace-Id", traceId);
        }
    }

    @Override
    public void onStepStarted(String sagaName, String sagaId, String stepId) {
        Span parent = sagaSpans.get(sagaId);
        Span span = (parent != null ? tracer.nextSpan(parent) : tracer.nextSpan())
                .name("step:" + stepId)
                .start();
        stepSpans.put(key(sagaId, stepId), span);
    }

    @Override
    public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {
        endStepSpan(sagaId, stepId, null);
    }

    @Override
    public void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) {
        endStepSpan(sagaId, stepId, error);
    }

    @Override
    public void onCompleted(String sagaName, String sagaId, boolean success) {
        Span span = sagaSpans.remove(sagaId);
        if (span != null) {
            if (!success) {
                span.tag("outcome", "failed");
            } else {
                span.tag("outcome", "success");
            }
            span.end();
        }
    }

    private void endStepSpan(String sagaId, String stepId, Throwable error) {
        Span span = stepSpans.remove(key(sagaId, stepId));
        if (span != null) {
            if (error != null) {
                span.error(error);
                span.tag("outcome", "failed");
            } else {
                span.tag("outcome", "success");
            }
            span.end();
        }
    }

    private static String key(String sagaId, String stepId) {
        return sagaId + ":" + stepId;
    }
}
