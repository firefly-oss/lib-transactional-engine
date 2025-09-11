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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects metrics for saga executions and provides insights into system performance.
 * Tracks execution times, success/failure rates, step performance, and optimization decisions.
 */
@Component
public class SagaMetricsCollector {
    
    private static final Logger log = LoggerFactory.getLogger(SagaMetricsCollector.class);
    
    // Saga execution metrics
    private final LongAdder sagasStarted = new LongAdder();
    private final LongAdder sagasCompleted = new LongAdder();
    private final LongAdder sagasFailed = new LongAdder();
    private final LongAdder sagasCompensated = new LongAdder();
    
    // Step execution metrics
    private final LongAdder stepsExecuted = new LongAdder();
    private final LongAdder stepsSucceeded = new LongAdder();
    private final LongAdder stepsFailed = new LongAdder();
    private final LongAdder stepsCompensated = new LongAdder();
    
    // Optimization metrics
    private final LongAdder optimizedContextsCreated = new LongAdder();
    private final LongAdder standardContextsCreated = new LongAdder();
    private final LongAdder backpressureApplications = new LongAdder();
    
    // Timing metrics
    private final Map<String, ExecutionTimer> sagaTimers = new ConcurrentHashMap<>();
    private final Map<String, ExecutionTimer> stepTimers = new ConcurrentHashMap<>();
    
    // Performance metrics
    private final AtomicLong totalExecutionTime = new AtomicLong(0);
    private final AtomicLong maxExecutionTime = new AtomicLong(0);
    private final AtomicLong minExecutionTime = new AtomicLong(Long.MAX_VALUE);
    
    /**
     * Records the start of a saga execution.
     */
    public void recordSagaStarted(String sagaName, String correlationId) {
        sagasStarted.increment();
        sagaTimers.put(correlationId, new ExecutionTimer(sagaName, Instant.now()));
        
        log.debug("Saga started: {} ({})", sagaName, correlationId);
    }
    
    /**
     * Records the completion of a saga execution.
     */
    public void recordSagaCompleted(String sagaName, String correlationId, Duration executionTime) {
        sagasCompleted.increment();
        recordExecutionTime(executionTime);
        sagaTimers.remove(correlationId);
        
        log.debug("Saga completed: {} ({}) in {}", sagaName, correlationId, executionTime);
    }
    
    /**
     * Records a saga failure.
     */
    public void recordSagaFailed(String sagaName, String correlationId, Throwable error) {
        sagasFailed.increment();
        ExecutionTimer timer = sagaTimers.remove(correlationId);
        if (timer != null) {
            Duration executionTime = Duration.between(timer.startTime, Instant.now());
            recordExecutionTime(executionTime);
        }
        
        log.debug("Saga failed: {} ({}) - {}", sagaName, correlationId, error.getMessage());
    }
    
    /**
     * Records a saga compensation.
     */
    public void recordSagaCompensated(String sagaName, String correlationId) {
        sagasCompensated.increment();
        ExecutionTimer timer = sagaTimers.remove(correlationId);
        if (timer != null) {
            Duration executionTime = Duration.between(timer.startTime, Instant.now());
            recordExecutionTime(executionTime);
        }
        
        log.debug("Saga compensated: {} ({})", sagaName, correlationId);
    }
    
    /**
     * Records the start of a step execution.
     */
    public void recordStepStarted(String stepId, String sagaName, String correlationId) {
        stepsExecuted.increment();
        stepTimers.put(correlationId + ":" + stepId, new ExecutionTimer(stepId, Instant.now()));
        
        log.debug("Step started: {} in saga {} ({})", stepId, sagaName, correlationId);
    }
    
    /**
     * Records a successful step execution.
     */
    public void recordStepSucceeded(String stepId, String sagaName, String correlationId) {
        stepsSucceeded.increment();
        stepTimers.remove(correlationId + ":" + stepId);
        
        log.debug("Step succeeded: {} in saga {} ({})", stepId, sagaName, correlationId);
    }
    
    /**
     * Records a failed step execution.
     */
    public void recordStepFailed(String stepId, String sagaName, String correlationId, Throwable error) {
        stepsFailed.increment();
        stepTimers.remove(correlationId + ":" + stepId);
        
        log.debug("Step failed: {} in saga {} ({}) - {}", stepId, sagaName, correlationId, error.getMessage());
    }
    
    /**
     * Records a step compensation.
     */
    public void recordStepCompensated(String stepId, String sagaName, String correlationId) {
        stepsCompensated.increment();
        
        log.debug("Step compensated: {} in saga {} ({})", stepId, sagaName, correlationId);
    }
    
    /**
     * Records the creation of an optimized context.
     */
    public void recordOptimizedContextCreated(String sagaName) {
        optimizedContextsCreated.increment();
        
        log.debug("Optimized context created for saga: {}", sagaName);
    }
    
    /**
     * Records the creation of a standard context.
     */
    public void recordStandardContextCreated(String sagaName) {
        standardContextsCreated.increment();
        
        log.debug("Standard context created for saga: {}", sagaName);
    }
    
    /**
     * Records the application of backpressure.
     */
    public void recordBackpressureApplied(String strategy, int itemCount) {
        backpressureApplications.increment();
        
        log.debug("Backpressure applied: {} strategy for {} items", strategy, itemCount);
    }
    
    /**
     * Gets current metrics snapshot.
     */
    public MetricsSnapshot getMetrics() {
        return new MetricsSnapshot(
            sagasStarted.sum(),
            sagasCompleted.sum(),
            sagasFailed.sum(),
            sagasCompensated.sum(),
            stepsExecuted.sum(),
            stepsSucceeded.sum(),
            stepsFailed.sum(),
            stepsCompensated.sum(),
            optimizedContextsCreated.sum(),
            standardContextsCreated.sum(),
            backpressureApplications.sum(),
            calculateAverageExecutionTime(),
            maxExecutionTime.get(),
            minExecutionTime.get() == Long.MAX_VALUE ? 0 : minExecutionTime.get(),
            sagaTimers.size(),
            stepTimers.size()
        );
    }
    
    /**
     * Resets all metrics (useful for testing).
     */
    public void reset() {
        sagasStarted.reset();
        sagasCompleted.reset();
        sagasFailed.reset();
        sagasCompensated.reset();
        stepsExecuted.reset();
        stepsSucceeded.reset();
        stepsFailed.reset();
        stepsCompensated.reset();
        optimizedContextsCreated.reset();
        standardContextsCreated.reset();
        backpressureApplications.reset();
        sagaTimers.clear();
        stepTimers.clear();
        totalExecutionTime.set(0);
        maxExecutionTime.set(0);
        minExecutionTime.set(Long.MAX_VALUE);
    }
    
    private void recordExecutionTime(Duration executionTime) {
        long millis = executionTime.toMillis();
        totalExecutionTime.addAndGet(millis);
        
        // Update max
        long currentMax = maxExecutionTime.get();
        while (millis > currentMax && !maxExecutionTime.compareAndSet(currentMax, millis)) {
            currentMax = maxExecutionTime.get();
        }
        
        // Update min
        long currentMin = minExecutionTime.get();
        while (millis < currentMin && !minExecutionTime.compareAndSet(currentMin, millis)) {
            currentMin = minExecutionTime.get();
        }
    }
    
    private long calculateAverageExecutionTime() {
        long completed = sagasCompleted.sum() + sagasFailed.sum() + sagasCompensated.sum();
        return completed > 0 ? totalExecutionTime.get() / completed : 0;
    }
    
    /**
     * Internal timer for tracking execution times.
     */
    private static class ExecutionTimer {
        final String name;
        final Instant startTime;
        
        ExecutionTimer(String name, Instant startTime) {
            this.name = name;
            this.startTime = startTime;
        }
    }
    
    /**
     * Immutable snapshot of current metrics.
     */
    public static class MetricsSnapshot {
        private final long sagasStarted;
        private final long sagasCompleted;
        private final long sagasFailed;
        private final long sagasCompensated;
        private final long stepsExecuted;
        private final long stepsSucceeded;
        private final long stepsFailed;
        private final long stepsCompensated;
        private final long optimizedContextsCreated;
        private final long standardContextsCreated;
        private final long backpressureApplications;
        private final long averageExecutionTimeMs;
        private final long maxExecutionTimeMs;
        private final long minExecutionTimeMs;
        private final int activeSagas;
        private final int activeSteps;
        
        public MetricsSnapshot(long sagasStarted, long sagasCompleted, long sagasFailed, long sagasCompensated,
                              long stepsExecuted, long stepsSucceeded, long stepsFailed, long stepsCompensated,
                              long optimizedContextsCreated, long standardContextsCreated, long backpressureApplications,
                              long averageExecutionTimeMs, long maxExecutionTimeMs, long minExecutionTimeMs,
                              int activeSagas, int activeSteps) {
            this.sagasStarted = sagasStarted;
            this.sagasCompleted = sagasCompleted;
            this.sagasFailed = sagasFailed;
            this.sagasCompensated = sagasCompensated;
            this.stepsExecuted = stepsExecuted;
            this.stepsSucceeded = stepsSucceeded;
            this.stepsFailed = stepsFailed;
            this.stepsCompensated = stepsCompensated;
            this.optimizedContextsCreated = optimizedContextsCreated;
            this.standardContextsCreated = standardContextsCreated;
            this.backpressureApplications = backpressureApplications;
            this.averageExecutionTimeMs = averageExecutionTimeMs;
            this.maxExecutionTimeMs = maxExecutionTimeMs;
            this.minExecutionTimeMs = minExecutionTimeMs;
            this.activeSagas = activeSagas;
            this.activeSteps = activeSteps;
        }
        
        // Getters
        public long getSagasStarted() { return sagasStarted; }
        public long getSagasCompleted() { return sagasCompleted; }
        public long getSagasFailed() { return sagasFailed; }
        public long getSagasCompensated() { return sagasCompensated; }
        public long getStepsExecuted() { return stepsExecuted; }
        public long getStepsSucceeded() { return stepsSucceeded; }
        public long getStepsFailed() { return stepsFailed; }
        public long getStepsCompensated() { return stepsCompensated; }
        public long getOptimizedContextsCreated() { return optimizedContextsCreated; }
        public long getStandardContextsCreated() { return standardContextsCreated; }
        public long getBackpressureApplications() { return backpressureApplications; }
        public long getAverageExecutionTimeMs() { return averageExecutionTimeMs; }
        public long getMaxExecutionTimeMs() { return maxExecutionTimeMs; }
        public long getMinExecutionTimeMs() { return minExecutionTimeMs; }
        public int getActiveSagas() { return activeSagas; }
        public int getActiveSteps() { return activeSteps; }
        
        public double getSagaSuccessRate() {
            long total = sagasCompleted + sagasFailed;
            return total > 0 ? (double) sagasCompleted / total : 0.0;
        }
        
        public double getStepSuccessRate() {
            long total = stepsSucceeded + stepsFailed;
            return total > 0 ? (double) stepsSucceeded / total : 0.0;
        }
        
        public double getOptimizationRate() {
            long total = optimizedContextsCreated + standardContextsCreated;
            return total > 0 ? (double) optimizedContextsCreated / total : 0.0;
        }
    }
}
