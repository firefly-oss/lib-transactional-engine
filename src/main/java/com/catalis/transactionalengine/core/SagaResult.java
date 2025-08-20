package com.catalis.transactionalengine.core;

import com.catalis.transactionalengine.annotations.SagaStep;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
            boolean compensated
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

    /** Build a SagaResult snapshot from context and engine-provided details. */
    public static SagaResult from(
            String sagaName,
            SagaContext ctx,
            Map<String, Boolean> compensatedFlags,
            Map<String, Throwable> stepErrors
    ) {
        Instant started = ctx.startedAt();
        Instant completed = Instant.now();
        // Use step results keys to preserve insertion order where possible
        Map<String, StepOutcome> stepMap = ctx.stepResultsView().keySet().stream()
                .collect(Collectors.toMap(
                        k -> k,
                        k -> new StepOutcome(
                                ctx.getStatus(k),
                                ctx.getAttempts(k),
                                ctx.getLatency(k),
                                ctx.getResult(k),
                                stepErrors.get(k),
                                Boolean.TRUE.equals(compensatedFlags.get(k))
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
}
