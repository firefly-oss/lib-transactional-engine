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

import io.micrometer.core.instrument.*;

/**
 * Micrometer-based implementation of SagaEvents that publishes counters, timers,
 * and distribution summaries for steps and saga completion.
 */
public class SagaMicrometerEvents implements SagaEvents {
    private final MeterRegistry registry;

    public SagaMicrometerEvents(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onStepStarted(String sagaName, String sagaId, String stepId) {
        registry.counter("saga.step.started", Tags.of(Tag.of("saga", sagaName), Tag.of("step", stepId))).increment();
    }

    @Override
    public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {
        Tags tags = Tags.of(Tag.of("saga", sagaName), Tag.of("step", stepId), Tag.of("outcome", "success"));
        registry.counter("saga.step.completed", tags).increment();
        Timer.builder("saga.step.latency")
                .tags(tags)
                .register(registry)
                .record(java.time.Duration.ofMillis(Math.max(0L, latencyMs)));
        DistributionSummary.builder("saga.step.attempts")
                .baseUnit("attempts")
                .tags(tags)
                .register(registry)
                .record(Math.max(0, attempts));
    }

    @Override
    public void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) {
        Tags tags = Tags.of(Tag.of("saga", sagaName), Tag.of("step", stepId), Tag.of("outcome", "failed"));
        registry.counter("saga.step.completed", tags).increment();
        Timer.builder("saga.step.latency")
                .tags(tags)
                .register(registry)
                .record(java.time.Duration.ofMillis(Math.max(0L, latencyMs)));
        DistributionSummary.builder("saga.step.attempts")
                .baseUnit("attempts")
                .tags(tags)
                .register(registry)
                .record(Math.max(0, attempts));
    }

    @Override
    public void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) {
        String outcome = error == null ? "success" : "error";
        registry.counter("saga.step.compensated", Tags.of(Tag.of("saga", sagaName), Tag.of("step", stepId), Tag.of("outcome", outcome))).increment();
    }

    @Override
    public void onCompleted(String sagaName, String sagaId, boolean success) {
        registry.counter("saga.run.completed", Tags.of(Tag.of("saga", sagaName), Tag.of("success", String.valueOf(success)))).increment();
    }
}
