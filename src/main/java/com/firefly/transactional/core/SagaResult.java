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


package com.firefly.transactional.core;

import com.firefly.transactional.annotations.SagaStep;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable, developer-friendly snapshot of a Saga execution.
 * Captures top-level metadata and per-step outcomes while providing typed accessors.
 */
public final class SagaResult {
    private final String sagaName;
    private final String correlationId;
    private final Instant startedAt;
    private final Instant completedAt;
    private final boolean success;
    private final Throwable error; // null if success
    private final Map<String, String> headers; // snapshot
    private final Map<String, StepOutcome> steps; // unmodifiable

    public record StepOutcome(
            StepStatus status,
            int attempts,
            long latencyMs,
            Object result,
            Throwable error,
            boolean compensated,
            Instant startedAt,
            Object compensationResult,
            Throwable compensationError
    ) {}

    private SagaResult(
            String sagaName,
            String correlationId,
            Instant startedAt,
            Instant completedAt,
            boolean success,
            Throwable error,
            Map<String, String> headers,
            Map<String, StepOutcome> steps
    ) {
        this.sagaName = sagaName;
        this.correlationId = correlationId;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.success = success;
        this.error = error;
        this.headers = headers;
        this.steps = steps;
    }

    public String sagaName() { return sagaName; }
    public String correlationId() { return correlationId; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public Duration duration() { return Duration.between(startedAt, completedAt); }
    public boolean isSuccess() { return success; }
    public Optional<Throwable> error() { return Optional.ofNullable(error); }
    public Map<String, String> headers() { return headers; }
    public Map<String, StepOutcome> steps() { return steps; }

    // Helpers
    public Optional<String> firstErrorStepId() {
        return steps.entrySet().stream()
                .filter(e -> e.getValue().error() != null || StepStatus.FAILED.equals(e.getValue().status()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    public java.util.List<String> failedSteps() {
        return steps.entrySet().stream()
                .filter(e -> e.getValue().error() != null || StepStatus.FAILED.equals(e.getValue().status()))
                .map(Map.Entry::getKey)
                .toList();
    }

    public java.util.List<String> compensatedSteps() {
        return steps.entrySet().stream()
                .filter(e -> e.getValue().compensated())
                .map(Map.Entry::getKey)
                .toList();
    }

    // Typed accessors by step id
    @SuppressWarnings("unchecked")
    public <T> Optional<T> resultOf(String stepId, Class<T> type) {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(type, "type");
        StepOutcome out = steps.get(stepId);
        if (out == null || out.result() == null) return Optional.empty();
        Object r = out.result();
        return type.isInstance(r) ? Optional.of((T) r) : Optional.empty();
    }

    // Convenience for method reference annotated with @SagaStep
    public <T> Optional<T> resultOf(Method stepMethod, Class<T> type) {
        String id = extractId(stepMethod);
        return resultOf(id, type);
    }

    private static String extractId(Method m) {
        SagaStep ann = m.getAnnotation(SagaStep.class);
        if (ann == null) {
            throw new IllegalArgumentException("Method " + m + " is not annotated with @SagaStep");
        }
        return ann.id();
    }

    /** Build a SagaResult snapshot from context and engine-provided details.
     * Includes all steps known by status in the context (may have null results).
     * Prefer using the overload that accepts allStepIds to preserve declaration order across runs.
     */
    public static SagaResult from(
            String sagaName,
            SagaContext ctx,
            Map<String, Boolean> compensatedFlags,
            Map<String, Throwable> stepErrors
    ) {
        Instant started = ctx.startedAt();
        Instant completed = Instant.now();
        Map<String, StepOutcome> stepMap = ctx.stepStatusesView().keySet().stream()
                .collect(Collectors.toMap(
                        k -> k,
                        k -> new StepOutcome(
                                ctx.getStatus(k),
                                ctx.getAttempts(k),
                                ctx.getLatency(k),
                                ctx.getResult(k),
                                stepErrors.get(k),
                                Boolean.TRUE.equals(compensatedFlags.get(k)),
                                ctx.getStepStartedAt(k),
                                ctx.getCompensationResult(k),
                                ctx.getCompensationError(k)
                        ),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
        boolean success = stepErrors.isEmpty();
        Throwable primary = success ? null : stepErrors.values().stream().findFirst().orElse(null);
        return new SagaResult(
                sagaName,
                ctx.correlationId(),
                started,
                completed,
                success,
                primary,
                Collections.unmodifiableMap(new LinkedHashMap<>(ctx.headers())),
                Collections.unmodifiableMap(stepMap)
        );
    }

    /** Build a SagaResult snapshot including all known step ids (even if they produced no result). */
    public static SagaResult from(
            String sagaName,
            SagaContext ctx,
            Map<String, Boolean> compensatedFlags,
            Map<String, Throwable> stepErrors,
            java.util.Collection<String> allStepIds
    ) {
        Instant started = ctx.startedAt();
        Instant completed = Instant.now();
        LinkedHashMap<String, StepOutcome> stepMap = new LinkedHashMap<>();
        for (String k : allStepIds) {
            stepMap.put(k, new StepOutcome(
                    ctx.getStatus(k),
                    ctx.getAttempts(k),
                    ctx.getLatency(k),
                    ctx.getResult(k),
                    stepErrors.get(k),
                    Boolean.TRUE.equals(compensatedFlags.get(k)),
                    ctx.getStepStartedAt(k),
                    ctx.getCompensationResult(k),
                    ctx.getCompensationError(k)
            ));
        }
        boolean success = stepErrors.isEmpty();
        Throwable primary = success ? null : stepErrors.values().stream().findFirst().orElse(null);
        return new SagaResult(
                sagaName,
                ctx.correlationId(),
                started,
                completed,
                success,
                primary,
                Collections.unmodifiableMap(new LinkedHashMap<>(ctx.headers())),
                Collections.unmodifiableMap(stepMap)
        );
    }
}
