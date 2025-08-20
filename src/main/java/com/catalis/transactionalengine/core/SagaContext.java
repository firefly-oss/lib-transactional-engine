package com.catalis.transactionalengine.core;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime context for a single Saga execution (in-memory only).
 * <p>
 * Holds per-execution data such as:
 * - Correlation id (UUID by default) and outbound headers to propagate (e.g., user id).
 * - Step results, statuses, attempts, latencies, and per-step start timestamps.
 * - A set of idempotency keys used to skip steps within the same run when configured.
 *
 * Thread-safe for concurrent updates from steps executing in the same layer.
 */
public class SagaContext {
    private final String correlationId;
    private final Map<String, String> headers = new ConcurrentHashMap<>();

    private final Map<String, Object> stepResults = new ConcurrentHashMap<>();
    private final Map<String, StepStatus> stepStatuses = new ConcurrentHashMap<>();
    private final Map<String, Integer> stepAttempts = new ConcurrentHashMap<>();
    private final Map<String, Long> stepLatenciesMs = new ConcurrentHashMap<>();
    private final Map<String, Instant> stepStartedAt = new ConcurrentHashMap<>();
    private final Set<String> idempotencyKeys = ConcurrentHashMap.newKeySet();
    private final Instant startedAt = Instant.now();

    public SagaContext() {
        this(UUID.randomUUID().toString());
    }

    public SagaContext(String correlationId) {
        this.correlationId = correlationId;
    }

    public String correlationId() {
        return correlationId;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public void putHeader(String key, String value) {
        headers.put(key, value);
    }

    public Object getResult(String stepId) {
        return stepResults.get(stepId);
    }

    public void putResult(String stepId, Object value) {
        if (value != null) {
            stepResults.put(stepId, value);
        }
    }

    public StepStatus getStatus(String stepId) {
        return stepStatuses.get(stepId);
    }

    public void setStatus(String stepId, StepStatus status) {
        stepStatuses.put(stepId, status);
    }

    public int incrementAttempts(String stepId) {
        return stepAttempts.merge(stepId, 1, Integer::sum);
    }

    public int getAttempts(String stepId) {
        return stepAttempts.getOrDefault(stepId, 0);
    }

    public void setLatency(String stepId, long millis) {
        stepLatenciesMs.put(stepId, millis);
    }

    public long getLatency(String stepId) {
        return stepLatenciesMs.getOrDefault(stepId, 0L);
    }

    public void markStepStarted(String stepId, Instant when) {
        if (when != null) stepStartedAt.put(stepId, when);
    }

    public Instant getStepStartedAt(String stepId) {
        return stepStartedAt.get(stepId);
    }

    public boolean markIdempotent(String key) {
        return idempotencyKeys.add(key);
    }

    public boolean hasIdempotencyKey(String key) {
        return idempotencyKeys.contains(key);
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Map<String, Object> stepResultsView() {
        return Collections.unmodifiableMap(stepResults);
    }

    public Map<String, StepStatus> stepStatusesView() {
        return Collections.unmodifiableMap(stepStatuses);
    }

    public Map<String, Integer> stepAttemptsView() {
        return Collections.unmodifiableMap(stepAttempts);
    }

    public Map<String, Long> stepLatenciesView() {
        return Collections.unmodifiableMap(stepLatenciesMs);
    }

    public Map<String, Instant> stepStartedAtView() {
        return Collections.unmodifiableMap(stepStartedAt);
    }
}
