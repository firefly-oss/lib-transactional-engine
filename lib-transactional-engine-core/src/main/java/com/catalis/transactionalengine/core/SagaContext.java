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
 * - A general-purpose variables store for cross-step data exchange.
 * - Step results, statuses, attempts, latencies, and per-step start timestamps.
 * - Compensation results and errors for post-mortem/observability reporting.
 * - A set of idempotency keys used to skip steps within the same run when configured.
 * - The sagaName corresponding to the {@code @Saga(name=...)} being executed.
 *
 * Thread-safe for concurrent updates from steps executing in the same layer.
 */
public class SagaContext {
    private final String correlationId;
    private String sagaName; // optional; set by engine based on @Saga.name
    private final Map<String, String> headers = new ConcurrentHashMap<>();
    private final Map<String, Object> variables = new ConcurrentHashMap<>();

    private final Map<String, Object> stepResults = new ConcurrentHashMap<>();
    private final Map<String, StepStatus> stepStatuses = new ConcurrentHashMap<>();
    private final Map<String, Integer> stepAttempts = new ConcurrentHashMap<>();
    private final Map<String, Long> stepLatenciesMs = new ConcurrentHashMap<>();
    private final Map<String, Instant> stepStartedAt = new ConcurrentHashMap<>();
    private final Map<String, Object> compensationResults = new ConcurrentHashMap<>();
    private final Map<String, Throwable> compensationErrors = new ConcurrentHashMap<>();
    private final Set<String> idempotencyKeys = ConcurrentHashMap.newKeySet();
    private final Instant startedAt = Instant.now();

    // Topology snapshot for this execution (set by the engine before starting steps)
    private volatile java.util.List<java.util.List<String>> topologyLayers;
    private final Map<String, java.util.List<String>> stepDependencies = new ConcurrentHashMap<>();

    public SagaContext() {
        this(UUID.randomUUID().toString());
    }

    public SagaContext(String correlationId) {
        this.correlationId = correlationId;
    }

    public SagaContext(String correlationId, String sagaName) {
        this.correlationId = correlationId;
        this.sagaName = sagaName;
    }

    public String correlationId() {
        return correlationId;
    }

    /** Name of the saga being executed (from @Saga.name). May be null if not set. */
    public String sagaName() {
        return sagaName;
    }

    /** Set by the engine when executing a saga; can be used by application code if needed. */
    public void setSagaName(String sagaName) {
        this.sagaName = sagaName;
    }

    // Headers
    public Map<String, String> headers() {
        return headers;
    }

    public void putHeader(String key, String value) {
        headers.put(key, value);
    }

    // Variables
    /** Returns a live, thread-safe map for variables storage. */
    public Map<String, Object> variables() {
        return variables;
    }

    public void putVariable(String name, Object value) {
        if (name == null) return;
        if (value == null) {
            variables.remove(name);
        } else {
            variables.put(name, value);
        }
    }

    public Object getVariable(String name) {
        return variables.get(name);
    }

    public <T> T getVariableAs(String name, Class<T> type) {
        Object v = variables.get(name);
        if (v == null) return null;
        if (type.isInstance(v)) return type.cast(v);
        throw new ClassCastException("Variable '" + name + "' is of type " + v.getClass().getName() + " and cannot be cast to " + type.getName());
    }

    public void removeVariable(String name) {
        variables.remove(name);
    }

    // Step bookkeeping
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

    // Views
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

    // Compensation bookkeeping
    public void putCompensationResult(String stepId, Object value) {
        if (value != null) {
            compensationResults.put(stepId, value);
        }
    }

    public Object getCompensationResult(String stepId) {
        return compensationResults.get(stepId);
    }

    public void putCompensationError(String stepId, Throwable error) {
        if (error != null) {
            compensationErrors.put(stepId, error);
        }
    }

    public Throwable getCompensationError(String stepId) {
        return compensationErrors.get(stepId);
    }

    public Map<String, Object> compensationResultsView() {
        return Collections.unmodifiableMap(compensationResults);
    }

    public Map<String, Throwable> compensationErrorsView() {
        return Collections.unmodifiableMap(compensationErrors);
    }

    // Topology accessors
    /**
     * Set the execution layers (topological levels) for this run.
     * Intended to be set by the engine prior to step execution.
     */
    public void setTopologyLayers(java.util.List<java.util.List<String>> layers) {
        this.topologyLayers = layers;
    }

    /** Returns the execution layers for this run (may be null if not set). */
    public java.util.List<java.util.List<String>> topologyLayersView() {
        return topologyLayers == null ? java.util.List.of() : java.util.Collections.unmodifiableList(topologyLayers);
    }

    /** Replace the per-step dependency mapping (stepId -> dependsOn list). */
    public void setStepDependencies(Map<String, java.util.List<String>> deps) {
        stepDependencies.clear();
        if (deps != null) {
            for (Map.Entry<String, java.util.List<String>> e : deps.entrySet()) {
                stepDependencies.put(e.getKey(), java.util.List.copyOf(e.getValue()));
            }
        }
    }

    /** View of per-step dependencies (stepId -> dependsOn list). */
    public Map<String, java.util.List<String>> stepDependenciesView() {
        return java.util.Collections.unmodifiableMap(stepDependencies);
    }
}
