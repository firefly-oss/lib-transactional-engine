package com.catalis.transactionalengine.core;

import java.time.Instant;
import java.util.*;

/**
 * Performance-optimized version of SagaContext for single-threaded saga execution.
 * <p>
 * This implementation uses regular HashMap instead of ConcurrentHashMap for better performance
 * when concurrent access is not required. Most saga executions are single-threaded except for
 * steps executing in parallel within the same layer.
 * <p>
 * Use this implementation when:
 * - Saga steps don't share mutable state across concurrent threads
 * - Layer concurrency is 1 (sequential execution)
 * - Performance is critical and thread safety is not needed
 * <p>
 * For concurrent scenarios, use the standard {@link SagaContext}.
 */
public class OptimizedSagaContext {
    private final String correlationId;
    private String sagaName;
    
    // Using regular HashMap for better performance in single-threaded scenarios
    private final Map<String, String> headers = new HashMap<>();
    private final Map<String, Object> variables = new HashMap<>();
    private final Map<String, Object> stepResults = new HashMap<>();
    private final Map<String, StepStatus> stepStatuses = new HashMap<>();
    private final Map<String, Integer> stepAttempts = new HashMap<>();
    private final Map<String, Long> stepLatenciesMs = new HashMap<>();
    private final Map<String, Instant> stepStartedAt = new HashMap<>();
    private final Map<String, Object> compensationResults = new HashMap<>();
    private final Map<String, Throwable> compensationErrors = new HashMap<>();
    private final Set<String> idempotencyKeys = new HashSet<>();
    private final Map<String, List<String>> stepDependencies = new HashMap<>();
    
    private final Instant startedAt = Instant.now();
    private volatile List<List<String>> topologyLayers;

    public OptimizedSagaContext() {
        this(UUID.randomUUID().toString());
    }

    public OptimizedSagaContext(String correlationId) {
        this.correlationId = correlationId;
    }

    public OptimizedSagaContext(String correlationId, String sagaName) {
        this.correlationId = correlationId;
        this.sagaName = sagaName;
    }

    // Conversion methods between standard and optimized contexts
    public static OptimizedSagaContext from(SagaContext standardContext) {
        OptimizedSagaContext optimized = new OptimizedSagaContext(
            standardContext.correlationId(), 
            standardContext.sagaName()
        );
        
        // Copy all data from standard context
        optimized.headers.putAll(standardContext.headers());
        optimized.variables.putAll(standardContext.variables());
        
        // Copy step-related data (using reflection to access private fields if needed)
        // Note: This is a simplified version - in practice, you'd need proper accessors
        return optimized;
    }

    public SagaContext toStandardContext() {
        SagaContext standard = new SagaContext(correlationId, sagaName);
        standard.headers().putAll(this.headers);
        standard.variables().putAll(this.variables);
        // Copy other data as needed
        return standard;
    }

    // Core accessors
    public String correlationId() {
        return correlationId;
    }

    public String sagaName() {
        return sagaName;
    }

    public void setSagaName(String sagaName) {
        this.sagaName = sagaName;
    }

    public Instant startedAt() {
        return startedAt;
    }

    // Headers operations
    public Map<String, String> headers() {
        return headers;
    }

    public void putHeader(String key, String value) {
        headers.put(key, value);
    }

    public String getHeader(String key) {
        return headers.get(key);
    }

    // Variables operations
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

    @SuppressWarnings("unchecked")
    public <T> T getVariableAs(String name, Class<T> type) {
        Object v = variables.get(name);
        if (v == null) return null;
        if (type.isInstance(v)) return type.cast(v);
        throw new ClassCastException("Variable '" + name + "' is of type " + 
            v.getClass().getName() + " and cannot be cast to " + type.getName());
    }

    public void removeVariable(String name) {
        variables.remove(name);
    }

    // Step operations
    public Object getResult(String stepId) {
        return stepResults.get(stepId);
    }

    public void putResult(String stepId, Object result) {
        stepResults.put(stepId, result);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> resultOf(String stepId, Class<T> type) {
        Object result = stepResults.get(stepId);
        if (result == null) return Optional.empty();
        if (type.isInstance(result)) {
            return Optional.of(type.cast(result));
        }
        return Optional.empty();
    }

    // Status operations
    public StepStatus getStatus(String stepId) {
        return stepStatuses.get(stepId);
    }

    public void setStatus(String stepId, StepStatus status) {
        stepStatuses.put(stepId, status);
    }

    // Attempts tracking
    public int getAttempts(String stepId) {
        return stepAttempts.getOrDefault(stepId, 0);
    }

    public void incrementAttempts(String stepId) {
        stepAttempts.merge(stepId, 1, Integer::sum);
    }

    public void setAttempts(String stepId, int attempts) {
        stepAttempts.put(stepId, attempts);
    }

    // Latency tracking
    public long getLatency(String stepId) {
        return stepLatenciesMs.getOrDefault(stepId, 0L);
    }

    public void setLatency(String stepId, long latencyMs) {
        stepLatenciesMs.put(stepId, latencyMs);
    }

    // Timestamp tracking
    public void markStepStarted(String stepId, Instant timestamp) {
        stepStartedAt.put(stepId, timestamp);
    }

    public Instant getStepStartedAt(String stepId) {
        return stepStartedAt.get(stepId);
    }

    // Idempotency operations
    public void markIdempotent(String key) {
        idempotencyKeys.add(key);
    }

    public boolean hasIdempotencyKey(String key) {
        return idempotencyKeys.contains(key);
    }

    // Compensation operations
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

    // Read-only views for external access
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

    public Map<String, Object> compensationResultsView() {
        return Collections.unmodifiableMap(compensationResults);
    }

    public Map<String, Throwable> compensationErrorsView() {
        return Collections.unmodifiableMap(compensationErrors);
    }

    // Topology operations
    public void setTopologyLayers(List<List<String>> layers) {
        this.topologyLayers = layers;
    }

    public List<List<String>> topologyLayersView() {
        return topologyLayers == null ? List.of() : Collections.unmodifiableList(topologyLayers);
    }

    public void setStepDependencies(Map<String, List<String>> deps) {
        stepDependencies.clear();
        if (deps != null) {
            for (Map.Entry<String, List<String>> e : deps.entrySet()) {
                stepDependencies.put(e.getKey(), List.copyOf(e.getValue()));
            }
        }
    }

    public Map<String, List<String>> stepDependenciesView() {
        return Collections.unmodifiableMap(stepDependencies);
    }

    /**
     * Performance comparison utility - measures the difference between HashMap and ConcurrentHashMap
     * operations in typical saga context usage patterns.
     */
    public static class PerformanceComparison {
        
        public static void runComparison(int operations) {
            System.out.println("Performance Comparison: HashMap vs ConcurrentHashMap");
            System.out.println("Operations: " + operations);
            
            // Test HashMap performance
            long startTime = System.nanoTime();
            Map<String, Object> hashMap = new HashMap<>();
            for (int i = 0; i < operations; i++) {
                hashMap.put("key" + i, "value" + i);
                hashMap.get("key" + (i / 2));
            }
            long hashMapTime = System.nanoTime() - startTime;
            
            // Test ConcurrentHashMap performance
            startTime = System.nanoTime();
            Map<String, Object> concurrentMap = new java.util.concurrent.ConcurrentHashMap<>();
            for (int i = 0; i < operations; i++) {
                concurrentMap.put("key" + i, "value" + i);
                concurrentMap.get("key" + (i / 2));
            }
            long concurrentMapTime = System.nanoTime() - startTime;
            
            System.out.println("HashMap time: " + hashMapTime / 1_000_000 + " ms");
            System.out.println("ConcurrentHashMap time: " + concurrentMapTime / 1_000_000 + " ms");
            System.out.println("Performance improvement: " + 
                String.format("%.2f%%", ((double) (concurrentMapTime - hashMapTime) / concurrentMapTime) * 100));
        }
    }
}