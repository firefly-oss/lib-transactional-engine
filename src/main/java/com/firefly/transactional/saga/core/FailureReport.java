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

import com.firefly.transactional.saga.core.SagaResult;
import com.firefly.transactional.shared.core.StepStatus;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Structured summary for partial failures of a Saga execution to aid incident triage.
 * Build from a SagaResult after execution completes (success=false).
 */
public final class FailureReport {
    private final String sagaName;
    private final String correlationId;
    private final boolean success;
    private final String errorSummary;
    private final Duration duration;
    private final List<Entry> failedSteps;
    private final List<String> compensatedSteps;
    private final Map<String, String> headers;

    public record Entry(String stepId, String status, int attempts, long latencyMs, String error) {}

    private FailureReport(String sagaName,
                          String correlationId,
                          boolean success,
                          String errorSummary,
                          Duration duration,
                          List<Entry> failedSteps,
                          List<String> compensatedSteps,
                          Map<String, String> headers) {
        this.sagaName = sagaName;
        this.correlationId = correlationId;
        this.success = success;
        this.errorSummary = errorSummary;
        this.duration = duration;
        this.failedSteps = failedSteps;
        this.compensatedSteps = compensatedSteps;
        this.headers = headers;
    }

    public String sagaName() { return sagaName; }
    public String correlationId() { return correlationId; }
    public boolean success() { return success; }
    public String errorSummary() { return errorSummary; }
    public Duration duration() { return duration; }
    public List<Entry> failedSteps() { return failedSteps; }
    public List<String> compensatedSteps() { return compensatedSteps; }
    public Map<String, String> headers() { return headers; }

    public static FailureReport from(SagaResult result) {
        Objects.requireNonNull(result, "result");
        String errorSummary = result.error().map(Throwable::toString).orElse("");
        List<Entry> failed = result.steps().entrySet().stream()
                .filter(e -> e.getValue().error() != null || StepStatus.FAILED.equals(e.getValue().status()))
                .map(e -> new Entry(
                        e.getKey(),
                        String.valueOf(e.getValue().status()),
                        e.getValue().attempts(),
                        e.getValue().latencyMs(),
                        e.getValue().error() == null ? "" : e.getValue().error().toString()
                ))
                .collect(Collectors.toList());
        List<String> compensated = result.compensatedSteps();
        Map<String, String> headersCopy = Collections.unmodifiableMap(new LinkedHashMap<>(result.headers()));
        return new FailureReport(
                result.sagaName(),
                result.correlationId(),
                result.isSuccess(),
                errorSummary,
                result.duration(),
                failed,
                compensated,
                headersCopy
        );
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Saga Failure Report: saga=").append(sagaName)
          .append(" correlationId=").append(correlationId)
          .append(" success=").append(success)
          .append(" durationMs=").append(duration.toMillis())
          .append(" error=").append(errorSummary).append('\n');
        if (!failedSteps.isEmpty()) {
            sb.append("Failed Steps:\n");
            for (Entry e : failedSteps) {
                sb.append(" - ").append(e.stepId())
                  .append(" status=").append(e.status())
                  .append(" attempts=").append(e.attempts())
                  .append(" latencyMs=").append(e.latencyMs())
                  .append(" error=").append(e.error()).append('\n');
            }
        }
        if (!compensatedSteps.isEmpty()) {
            sb.append("Compensated Steps: ").append(String.join(", ", compensatedSteps)).append('\n');
        }
        return sb.toString();
    }
}
