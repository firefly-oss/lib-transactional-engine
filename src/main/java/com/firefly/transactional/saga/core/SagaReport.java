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


package com.firefly.transactional.saga.core;

import com.firefly.transactional.shared.core.StepStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Immutable, user-friendly report for a saga execution.
 * Wraps SagaResult while exposing richer accessors, notably compensation outputs and errors.
 */
public final class SagaReport {
    public static final class StepReport {
        private final StepStatus status;
        private final int attempts;
        private final long latencyMs;
        private final Object result;
        private final Throwable error;
        private final boolean compensated;
        private final Instant startedAt;
        private final Object compensationResult;
        private final Throwable compensationError;

        private StepReport(StepStatus status, int attempts, long latencyMs, Object result, Throwable error,
                           boolean compensated, Instant startedAt, Object compensationResult, Throwable compensationError) {
            this.status = status;
            this.attempts = attempts;
            this.latencyMs = latencyMs;
            this.result = result;
            this.error = error;
            this.compensated = compensated;
            this.startedAt = startedAt;
            this.compensationResult = compensationResult;
            this.compensationError = compensationError;
        }

        public StepStatus status() { return status; }
        public int attempts() { return attempts; }
        public long latencyMs() { return latencyMs; }
        public Object result() { return result; }
        public Optional<Throwable> error() { return Optional.ofNullable(error); }
        public boolean compensated() { return compensated; }
        public Instant startedAt() { return startedAt; }
        public Object compensationResult() { return compensationResult; }
        public Optional<Throwable> compensationError() { return Optional.ofNullable(compensationError); }
    }

    private final String sagaName;
    private final String correlationId;
    private final Instant startedAt;
    private final Instant completedAt;
    private final boolean success;
    private final Map<String, String> headers;
    private final Map<String, StepReport> steps;

    private SagaReport(String sagaName, String correlationId, Instant startedAt, Instant completedAt,
                       boolean success, Map<String, String> headers, Map<String, StepReport> steps) {
        this.sagaName = sagaName;
        this.correlationId = correlationId;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.success = success;
        this.headers = headers;
        this.steps = steps;
    }

    public String sagaName() { return sagaName; }
    public String correlationId() { return correlationId; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public Duration duration() { return Duration.between(startedAt, completedAt); }
    public boolean isSuccess() { return success; }
    public Map<String, String> headers() { return headers; }
    public Map<String, StepReport> steps() { return steps; }

    public List<String> failedSteps() {
        return steps.entrySet().stream()
                .filter(e -> e.getValue().error().isPresent() || StepStatus.FAILED.equals(e.getValue().status()))
                .map(Map.Entry::getKey)
                .toList();
    }

    public List<String> compensatedSteps() {
        return steps.entrySet().stream()
                .filter(e -> e.getValue().compensated())
                .map(Map.Entry::getKey)
                .toList();
    }

    public static SagaReport from(SagaResult result) {
        Objects.requireNonNull(result, "result");
        Map<String, StepReport> stepReports = new LinkedHashMap<>();
        for (Map.Entry<String, SagaResult.StepOutcome> e : result.steps().entrySet()) {
            var so = e.getValue();
            var sr = new StepReport(
                    so.status(),
                    so.attempts(),
                    so.latencyMs(),
                    so.result(),
                    so.error(),
                    so.compensated(),
                    so.startedAt(),
                    so.compensationResult(),
                    so.compensationError()
            );
            stepReports.put(e.getKey(), sr);
        }
        return new SagaReport(
                result.sagaName(),
                result.correlationId(),
                result.startedAt(),
                result.completedAt(),
                result.isSuccess(),
                Collections.unmodifiableMap(new LinkedHashMap<>(result.headers())),
                Collections.unmodifiableMap(stepReports)
        );
    }
}
