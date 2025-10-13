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

import io.micrometer.core.instrument.*;
import java.time.Duration;

/**
 * Micrometer-based implementation of GenericTransactionalObserver.
 * <p>
 * This implementation publishes counters, timers, and distribution summaries
 * for transactional operations across different patterns (SAGA, TCC, etc.).
 */
public class MicrometerTransactionalObserver implements GenericTransactionalObserver {

    private final MeterRegistry registry;
    private final TransactionalMetricsCollector metricsCollector;

    public MicrometerTransactionalObserver(MeterRegistry registry, TransactionalMetricsCollector metricsCollector) {
        this.registry = registry;
        this.metricsCollector = metricsCollector;
    }
    
    @Override
    public void onTransactionStarted(String transactionType, String transactionName, String correlationId) {
        Tags tags = Tags.of(
            Tag.of("transaction.type", transactionType.toLowerCase()),
            Tag.of("transaction.name", transactionName)
        );
        registry.counter("transaction.started", tags).increment();
        metricsCollector.recordTransactionStarted(transactionType, transactionName, correlationId);
    }
    
    @Override
    public void onTransactionCompleted(String transactionType, String transactionName, String correlationId, boolean success, long durationMs) {
        Tags tags = Tags.of(
            Tag.of("transaction.type", transactionType.toLowerCase()),
            Tag.of("transaction.name", transactionName),
            Tag.of("outcome", success ? "success" : "failure")
        );
        registry.counter("transaction.completed", tags).increment();

        if (durationMs > 0) {
            registry.timer("transaction.duration", tags).record(Duration.ofMillis(durationMs));
        }

        metricsCollector.recordTransactionCompleted(transactionType, transactionName, correlationId, success, durationMs);
    }
    
    @Override
    public void onStepStarted(String transactionType, String transactionName, String correlationId, String stepId) {
        Tags tags = Tags.of(
            Tag.of("transaction.type", transactionType.toLowerCase()),
            Tag.of("transaction.name", transactionName),
            Tag.of("step.id", stepId)
        );
        registry.counter("step.started", tags).increment();
        metricsCollector.recordStepStarted(transactionType, transactionName, stepId);
    }
    
    @Override
    public void onStepSuccess(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs) {
        Tags tags = Tags.of(
            Tag.of("transaction.type", transactionType.toLowerCase()),
            Tag.of("transaction.name", transactionName),
            Tag.of("step.id", stepId),
            Tag.of("outcome", "success")
        );
        registry.counter("step.completed", tags).increment();
        
        if (durationMs > 0) {
            registry.timer("step.duration", tags).record(Duration.ofMillis(durationMs));
        }
        
        if (attempts > 1) {
            registry.counter("step.retries", tags).increment(attempts - 1);
        }
        
        DistributionSummary.builder("step.attempts")
                .baseUnit("attempts")
                .tags(tags)
                .register(registry)
                .record(attempts);

        metricsCollector.recordStepCompleted(transactionType, transactionName, stepId, attempts);
    }
    
    @Override
    public void onStepFailure(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs, Throwable error) {
        Tags tags = Tags.of(
            Tag.of("transaction.type", transactionType.toLowerCase()),
            Tag.of("transaction.name", transactionName),
            Tag.of("step.id", stepId),
            Tag.of("outcome", "failure"),
            Tag.of("error.type", error.getClass().getSimpleName())
        );
        registry.counter("step.completed", tags).increment();
        
        if (durationMs > 0) {
            registry.timer("step.duration", tags).record(Duration.ofMillis(durationMs));
        }
        
        if (attempts > 1) {
            registry.counter("step.retries", tags).increment(attempts - 1);
        }
        
        DistributionSummary.builder("step.attempts")
                .baseUnit("attempts")
                .tags(tags)
                .register(registry)
                .record(attempts);

        metricsCollector.recordStepFailed(transactionType, transactionName, stepId, attempts);
    }
    
    @Override
    public void onCompensationStarted(String transactionType, String transactionName, String correlationId, String stepId) {
        Tags tags = Tags.of(
            Tag.of("transaction.type", transactionType.toLowerCase()),
            Tag.of("transaction.name", transactionName),
            Tag.of("step.id", stepId)
        );
        registry.counter("compensation.started", tags).increment();
        metricsCollector.recordCompensationStarted(transactionType, transactionName, stepId);
    }
    
    @Override
    public void onCompensationSuccess(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs) {
        Tags tags = Tags.of(
            Tag.of("transaction.type", transactionType.toLowerCase()),
            Tag.of("transaction.name", transactionName),
            Tag.of("step.id", stepId),
            Tag.of("outcome", "success")
        );
        registry.counter("compensation.completed", tags).increment();
        
        if (durationMs > 0) {
            registry.timer("compensation.duration", tags).record(Duration.ofMillis(durationMs));
        }
        
        if (attempts > 1) {
            registry.counter("compensation.retries", tags).increment(attempts - 1);
        }

        metricsCollector.recordCompensationCompleted(transactionType, transactionName, stepId, true);
    }
    
    @Override
    public void onCompensationFailure(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs, Throwable error) {
        Tags tags = Tags.of(
            Tag.of("transaction.type", transactionType.toLowerCase()),
            Tag.of("transaction.name", transactionName),
            Tag.of("step.id", stepId),
            Tag.of("outcome", "failure"),
            Tag.of("error.type", error.getClass().getSimpleName())
        );
        registry.counter("compensation.completed", tags).increment();
        
        if (durationMs > 0) {
            registry.timer("compensation.duration", tags).record(Duration.ofMillis(durationMs));
        }
        
        if (attempts > 1) {
            registry.counter("compensation.retries", tags).increment(attempts - 1);
        }

        metricsCollector.recordCompensationCompleted(transactionType, transactionName, stepId, false);
    }
}
