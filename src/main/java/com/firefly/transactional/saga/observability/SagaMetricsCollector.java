/*
 * Copyright 2024 Firefly Authors
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

package com.firefly.transactional.saga.observability;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics collector specifically for SAGA transactions.
 * <p>
 * This class provides detailed metrics collection for SAGA execution,
 * including lifecycle events, step performance, optimization tracking,
 * and backpressure monitoring.
 */
@Component
public class SagaMetricsCollector {

    // Saga lifecycle metrics
    private final LongAdder sagasStarted = new LongAdder();
    private final LongAdder sagasCompleted = new LongAdder();
    private final LongAdder sagasFailed = new LongAdder();
    private final LongAdder sagasCompensated = new LongAdder();
    private final LongAdder activeSagas = new LongAdder();

    // Step metrics
    private final LongAdder stepsExecuted = new LongAdder();
    private final LongAdder stepsCompleted = new LongAdder();
    private final LongAdder stepsFailed = new LongAdder();
    private final LongAdder stepsCompensated = new LongAdder();
    private final LongAdder activeSteps = new LongAdder();

    // Optimization metrics
    private final LongAdder optimizedContextsCreated = new LongAdder();
    private final LongAdder standardContextsCreated = new LongAdder();

    // Backpressure metrics
    private final LongAdder backpressureApplications = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> backpressureByStrategy = new ConcurrentHashMap<>();

    // Execution time tracking
    private final AtomicLong totalExecutionTimeMs = new AtomicLong();
    private final AtomicLong maxExecutionTimeMs = new AtomicLong();
    private final AtomicLong minExecutionTimeMs = new AtomicLong(Long.MAX_VALUE);

    /**
     * Records that a saga has started.
     */
    public void recordSagaStarted(String sagaName, String correlationId) {
        sagasStarted.increment();
        activeSagas.increment();
    }

    /**
     * Records that a saga has completed successfully.
     */
    public void recordSagaCompleted(String sagaName, String correlationId) {
        sagasCompleted.increment();
        activeSagas.decrement();
    }

    /**
     * Records that a saga has completed successfully with execution time.
     */
    public void recordSagaCompleted(String sagaName, String correlationId, Duration executionTime) {
        recordSagaCompleted(sagaName, correlationId);
        recordExecutionTime(executionTime.toMillis());
    }

    /**
     * Records that a saga has failed.
     */
    public void recordSagaFailed(String sagaName, String correlationId) {
        sagasFailed.increment();
        activeSagas.decrement();
    }

    /**
     * Records that a saga has failed with an error.
     */
    public void recordSagaFailed(String sagaName, String correlationId, Throwable error) {
        recordSagaFailed(sagaName, correlationId);
    }

    /**
     * Records that a saga has been compensated.
     */
    public void recordSagaCompensated(String sagaName, String correlationId) {
        sagasCompensated.increment();
        activeSagas.decrement();
    }

    /**
     * Records that a step has started.
     */
    public void recordStepStarted(String stepId, String sagaName, String correlationId) {
        stepsExecuted.increment();
        activeSteps.increment();
    }

    /**
     * Records that a step has completed successfully.
     */
    public void recordStepCompleted(String stepId, String sagaName, String correlationId) {
        stepsCompleted.increment();
        activeSteps.decrement();
    }

    /**
     * Records that a step has succeeded (alias for recordStepCompleted).
     */
    public void recordStepSucceeded(String stepId, String sagaName, String correlationId) {
        recordStepCompleted(stepId, sagaName, correlationId);
    }

    /**
     * Records that a step has failed.
     */
    public void recordStepFailed(String stepId, String sagaName, String correlationId, Throwable error) {
        stepsFailed.increment();
        activeSteps.decrement();
    }

    /**
     * Records that a step has been compensated.
     */
    public void recordStepCompensated(String stepId, String sagaName, String correlationId) {
        stepsCompensated.increment();
    }

    /**
     * Records that an optimized context was created.
     */
    public void recordOptimizedContextCreated(String sagaName) {
        optimizedContextsCreated.increment();
    }

    /**
     * Records that a standard context was created.
     */
    public void recordStandardContextCreated(String sagaName) {
        standardContextsCreated.increment();
    }

    /**
     * Records that backpressure was applied.
     */
    public void recordBackpressureApplied(String strategy, int queueSize) {
        backpressureApplications.increment();
        backpressureByStrategy.computeIfAbsent(strategy, k -> new LongAdder()).increment();
    }

    /**
     * Records execution time for a saga.
     */
    private void recordExecutionTime(long durationMs) {
        totalExecutionTimeMs.addAndGet(durationMs);
        
        // Update max execution time
        long currentMax = maxExecutionTimeMs.get();
        while (durationMs > currentMax && !maxExecutionTimeMs.compareAndSet(currentMax, durationMs)) {
            currentMax = maxExecutionTimeMs.get();
        }
        
        // Update min execution time
        long currentMin = minExecutionTimeMs.get();
        while (durationMs < currentMin && !minExecutionTimeMs.compareAndSet(currentMin, durationMs)) {
            currentMin = minExecutionTimeMs.get();
        }
    }

    /**
     * Gets current metrics snapshot.
     */
    public MetricsSnapshot getMetrics() {
        return new MetricsSnapshot(this);
    }

    /**
     * Resets all metrics to zero.
     */
    public void reset() {
        sagasStarted.reset();
        sagasCompleted.reset();
        sagasFailed.reset();
        sagasCompensated.reset();
        activeSagas.reset();
        
        stepsExecuted.reset();
        stepsCompleted.reset();
        stepsFailed.reset();
        stepsCompensated.reset();
        activeSteps.reset();
        
        optimizedContextsCreated.reset();
        standardContextsCreated.reset();
        
        backpressureApplications.reset();
        backpressureByStrategy.clear();
        
        totalExecutionTimeMs.set(0);
        maxExecutionTimeMs.set(0);
        minExecutionTimeMs.set(Long.MAX_VALUE);
    }

    /**
     * Immutable snapshot of current metrics.
     */
    public static class MetricsSnapshot {
        private final long sagasStarted;
        private final long sagasCompleted;
        private final long sagasFailed;
        private final long sagasCompensated;
        private final long activeSagas;
        
        private final long stepsExecuted;
        private final long stepsCompleted;
        private final long stepsFailed;
        private final long stepsCompensated;
        private final long activeSteps;
        
        private final long optimizedContextsCreated;
        private final long standardContextsCreated;
        
        private final long backpressureApplications;
        
        private final long totalExecutionTimeMs;
        private final long maxExecutionTimeMs;
        private final long minExecutionTimeMs;

        private MetricsSnapshot(SagaMetricsCollector collector) {
            this.sagasStarted = collector.sagasStarted.sum();
            this.sagasCompleted = collector.sagasCompleted.sum();
            this.sagasFailed = collector.sagasFailed.sum();
            this.sagasCompensated = collector.sagasCompensated.sum();
            this.activeSagas = collector.activeSagas.sum();
            
            this.stepsExecuted = collector.stepsExecuted.sum();
            this.stepsCompleted = collector.stepsCompleted.sum();
            this.stepsFailed = collector.stepsFailed.sum();
            this.stepsCompensated = collector.stepsCompensated.sum();
            this.activeSteps = collector.activeSteps.sum();
            
            this.optimizedContextsCreated = collector.optimizedContextsCreated.sum();
            this.standardContextsCreated = collector.standardContextsCreated.sum();
            
            this.backpressureApplications = collector.backpressureApplications.sum();
            
            this.totalExecutionTimeMs = collector.totalExecutionTimeMs.get();
            this.maxExecutionTimeMs = collector.maxExecutionTimeMs.get();
            this.minExecutionTimeMs = collector.minExecutionTimeMs.get() == Long.MAX_VALUE ? 0 : collector.minExecutionTimeMs.get();
        }

        // Getters
        public long getSagasStarted() { return sagasStarted; }
        public long getSagasCompleted() { return sagasCompleted; }
        public long getSagasFailed() { return sagasFailed; }
        public long getSagasCompensated() { return sagasCompensated; }
        public long getActiveSagas() { return activeSagas; }
        
        public long getStepsExecuted() { return stepsExecuted; }
        public long getStepsCompleted() { return stepsCompleted; }
        public long getStepsSucceeded() { return stepsCompleted; } // Alias for getStepsCompleted
        public long getStepsFailed() { return stepsFailed; }
        public long getStepsCompensated() { return stepsCompensated; }
        public long getActiveSteps() { return activeSteps; }
        
        public long getOptimizedContextsCreated() { return optimizedContextsCreated; }
        public long getStandardContextsCreated() { return standardContextsCreated; }
        
        public long getBackpressureApplications() { return backpressureApplications; }
        
        public long getMaxExecutionTimeMs() { return maxExecutionTimeMs; }
        public long getMinExecutionTimeMs() { return minExecutionTimeMs; }
        
        // Calculated metrics
        public double getSagaSuccessRate() {
            long total = sagasCompleted + sagasFailed;
            return total == 0 ? 0.0 : (double) sagasCompleted / total;
        }
        
        public double getStepSuccessRate() {
            long total = stepsCompleted + stepsFailed;
            return total == 0 ? 0.0 : (double) stepsCompleted / total;
        }
        
        public double getOptimizationRate() {
            long total = optimizedContextsCreated + standardContextsCreated;
            return total == 0 ? 0.0 : (double) optimizedContextsCreated / total;
        }
        
        public long getAverageExecutionTimeMs() {
            long completedSagas = sagasCompleted + sagasFailed + sagasCompensated;
            return completedSagas == 0 ? 0 : totalExecutionTimeMs / completedSagas;
        }
    }
}
