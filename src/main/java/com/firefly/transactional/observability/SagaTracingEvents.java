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

import com.firefly.transactional.core.SagaContext;
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
