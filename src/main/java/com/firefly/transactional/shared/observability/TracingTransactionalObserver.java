/*
 * Copyright (c) 2023 Firefly Authors. All rights reserved.
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

package com.firefly.transactional.shared.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic tracing-based implementation of GenericTransactionalObserver.
 * <p>
 * Uses Micrometer Tracing to create distributed traces for transactional operations
 * across all transaction patterns (SAGA, TCC, etc.).
 */
public class TracingTransactionalObserver implements GenericTransactionalObserver {
    
    private final Tracer tracer;
    private final Map<String, Span> transactionSpans = new ConcurrentHashMap<>();
    private final Map<String, Span> stepSpans = new ConcurrentHashMap<>();
    
    public TracingTransactionalObserver(Tracer tracer) {
        this.tracer = tracer;
    }
    
    @Override
    public void onTransactionStarted(String transactionType, String transactionName, String correlationId) {
        Span span = tracer.nextSpan()
                .name(transactionType.toLowerCase() + ":" + transactionName)
                .tag("transaction.type", transactionType.toLowerCase())
                .tag("transaction.name", transactionName)
                .tag("correlation.id", correlationId)
                .start();
        
        transactionSpans.put(correlationId, span);
    }
    
    @Override
    public void onTransactionCompleted(String transactionType, String transactionName, String correlationId, boolean success, long durationMs) {
        Span span = transactionSpans.remove(correlationId);
        if (span != null) {
            span.tag("outcome", success ? "success" : "failure");
            if (durationMs > 0) {
                span.tag("duration.ms", String.valueOf(durationMs));
            }
            span.end();
        }
    }
    
    @Override
    public void onStepStarted(String transactionType, String transactionName, String correlationId, String stepId) {
        Span parentSpan = transactionSpans.get(correlationId);
        Span stepSpan = tracer.nextSpan(parentSpan)
                .name("step:" + stepId)
                .tag("transaction.type", transactionType.toLowerCase())
                .tag("transaction.name", transactionName)
                .tag("correlation.id", correlationId)
                .tag("step.id", stepId)
                .start();
        
        stepSpans.put(correlationId + ":" + stepId, stepSpan);
    }
    
    @Override
    public void onStepSuccess(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs) {
        String key = correlationId + ":" + stepId;
        Span span = stepSpans.remove(key);
        if (span != null) {
            span.tag("outcome", "success");
            span.tag("attempts", String.valueOf(attempts));
            if (durationMs > 0) {
                span.tag("duration.ms", String.valueOf(durationMs));
            }
            span.end();
        }
    }
    
    @Override
    public void onStepFailure(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs, Throwable error) {
        String key = correlationId + ":" + stepId;
        Span span = stepSpans.remove(key);
        if (span != null) {
            span.tag("outcome", "failure");
            span.tag("attempts", String.valueOf(attempts));
            if (durationMs > 0) {
                span.tag("duration.ms", String.valueOf(durationMs));
            }
            if (error != null) {
                span.tag("error.type", error.getClass().getSimpleName());
                span.tag("error.message", error.getMessage() != null ? error.getMessage() : "");
            }
            span.end();
        }
    }
    
    @Override
    public void onCompensationStarted(String transactionType, String transactionName, String correlationId, String stepId) {
        Span parentSpan = transactionSpans.get(correlationId);
        Span compensationSpan = tracer.nextSpan(parentSpan)
                .name("compensation:" + stepId)
                .tag("transaction.type", transactionType.toLowerCase())
                .tag("transaction.name", transactionName)
                .tag("correlation.id", correlationId)
                .tag("step.id", stepId)
                .tag("operation.type", "compensation")
                .start();
        
        stepSpans.put(correlationId + ":compensation:" + stepId, compensationSpan);
    }
    
    @Override
    public void onCompensationSuccess(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs) {
        String key = correlationId + ":compensation:" + stepId;
        Span span = stepSpans.remove(key);
        if (span != null) {
            span.tag("outcome", "success");
            span.tag("attempts", String.valueOf(attempts));
            if (durationMs > 0) {
                span.tag("duration.ms", String.valueOf(durationMs));
            }
            span.end();
        }
    }
    
    @Override
    public void onCompensationFailure(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs, Throwable error) {
        String key = correlationId + ":compensation:" + stepId;
        Span span = stepSpans.remove(key);
        if (span != null) {
            span.tag("outcome", "failure");
            span.tag("attempts", String.valueOf(attempts));
            if (durationMs > 0) {
                span.tag("duration.ms", String.valueOf(durationMs));
            }
            if (error != null) {
                span.tag("error.type", error.getClass().getSimpleName());
                span.tag("error.message", error.getMessage() != null ? error.getMessage() : "");
            }
            span.end();
        }
    }
}
