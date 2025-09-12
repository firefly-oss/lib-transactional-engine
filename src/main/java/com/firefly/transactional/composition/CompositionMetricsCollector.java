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

package com.firefly.transactional.composition;

import com.firefly.transactional.config.SagaCompositionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Collects comprehensive metrics for saga composition executions.
 * <p>
 * Provides detailed insights into composition performance, success rates,
 * execution patterns, and system health for monitoring and optimization.
 */
public class CompositionMetricsCollector {
    
    private static final Logger log = LoggerFactory.getLogger(CompositionMetricsCollector.class);
    
    private final SagaCompositionProperties.MetricsProperties properties;
    
    // Composition execution metrics
    private final LongAdder compositionsStarted = new LongAdder();
    private final LongAdder compositionsCompleted = new LongAdder();
    private final LongAdder compositionsFailed = new LongAdder();
    private final LongAdder compositionsCompensated = new LongAdder();
    
    // Saga-level metrics within compositions
    private final LongAdder sagasExecuted = new LongAdder();
    private final LongAdder sagasSucceeded = new LongAdder();
    private final LongAdder sagasFailed = new LongAdder();
    private final LongAdder sagasSkipped = new LongAdder();
    
    // Performance metrics
    private final AtomicLong totalExecutionTime = new AtomicLong(0);
    private final AtomicLong maxExecutionTime = new AtomicLong(0);
    private final AtomicLong minExecutionTime = new AtomicLong(Long.MAX_VALUE);
    
    // Detailed metrics by composition name
    private final Map<String, CompositionStats> compositionStats = new ConcurrentHashMap<>();
    
    // Recent execution history (for detailed analysis)
    private final List<CompositionExecution> recentExecutions = new ArrayList<>();
    private final ReentrantReadWriteLock executionsLock = new ReentrantReadWriteLock();
    
    // Active composition tracking
    private final Map<String, CompositionTimer> activeCompositions = new ConcurrentHashMap<>();
    
    public CompositionMetricsCollector(SagaCompositionProperties.MetricsProperties properties) {
        this.properties = properties;
        log.info("Composition metrics collector initialized with detailed={}, retention={}", 
                properties.isDetailed(), properties.getRetentionPeriod());
    }
    
    /**
     * Records the start of a composition execution.
     */
    public void recordCompositionStarted(String compositionName, String compositionId) {
        compositionsStarted.increment();
        activeCompositions.put(compositionId, new CompositionTimer(compositionName, Instant.now()));
        
        getOrCreateCompositionStats(compositionName).started.increment();
        
        log.debug("Composition started: {} ({})", compositionName, compositionId);
    }
    
    /**
     * Records the completion of a composition execution.
     */
    public void recordCompositionCompleted(String compositionName, String compositionId, 
                                         boolean success, Duration executionTime,
                                         int completedSagas, int failedSagas, int skippedSagas) {
        if (success) {
            compositionsCompleted.increment();
            getOrCreateCompositionStats(compositionName).completed.increment();
        } else {
            compositionsFailed.increment();
            getOrCreateCompositionStats(compositionName).failed.increment();
        }
        
        recordExecutionTime(executionTime);
        getOrCreateCompositionStats(compositionName).recordExecution(executionTime, success);
        
        // Record saga-level metrics
        sagasExecuted.add(completedSagas + failedSagas + skippedSagas);
        sagasSucceeded.add(completedSagas);
        sagasFailed.add(failedSagas);
        sagasSkipped.add(skippedSagas);
        
        activeCompositions.remove(compositionId);
        
        if (properties.isDetailed()) {
            recordDetailedExecution(compositionName, compositionId, success, executionTime, 
                                  completedSagas, failedSagas, skippedSagas);
        }
        
        log.debug("Composition completed: {} ({}) success={} duration={}", 
                 compositionName, compositionId, success, executionTime);
    }
    
    /**
     * Records a composition compensation.
     */
    public void recordCompositionCompensated(String compositionName, String compositionId) {
        compositionsCompensated.increment();
        getOrCreateCompositionStats(compositionName).compensated.increment();
        
        log.debug("Composition compensated: {} ({})", compositionName, compositionId);
    }
    
    /**
     * Gets current metrics snapshot.
     */
    public CompositionMetricsSnapshot getMetrics() {
        return new CompositionMetricsSnapshot(
            compositionsStarted.sum(),
            compositionsCompleted.sum(),
            compositionsFailed.sum(),
            compositionsCompensated.sum(),
            sagasExecuted.sum(),
            sagasSucceeded.sum(),
            sagasFailed.sum(),
            sagasSkipped.sum(),
            calculateAverageExecutionTime(),
            maxExecutionTime.get(),
            minExecutionTime.get() == Long.MAX_VALUE ? 0 : minExecutionTime.get(),
            activeCompositions.size(),
            new ConcurrentHashMap<>(compositionStats),
            getRecentExecutions()
        );
    }
    
    /**
     * Gets metrics for a specific composition.
     */
    public CompositionStats getCompositionStats(String compositionName) {
        return compositionStats.get(compositionName);
    }
    
    /**
     * Resets all metrics.
     */
    public void reset() {
        compositionsStarted.reset();
        compositionsCompleted.reset();
        compositionsFailed.reset();
        compositionsCompensated.reset();
        sagasExecuted.reset();
        sagasSucceeded.reset();
        sagasFailed.reset();
        sagasSkipped.reset();
        totalExecutionTime.set(0);
        maxExecutionTime.set(0);
        minExecutionTime.set(Long.MAX_VALUE);
        compositionStats.clear();
        
        executionsLock.writeLock().lock();
        try {
            recentExecutions.clear();
        } finally {
            executionsLock.writeLock().unlock();
        }
        
        log.info("Composition metrics reset");
    }
    
    private void recordExecutionTime(Duration executionTime) {
        long millis = executionTime.toMillis();
        totalExecutionTime.addAndGet(millis);
        
        // Update max
        maxExecutionTime.updateAndGet(current -> Math.max(current, millis));
        
        // Update min
        minExecutionTime.updateAndGet(current -> Math.min(current, millis));
    }
    
    private long calculateAverageExecutionTime() {
        long total = totalExecutionTime.get();
        long count = compositionsCompleted.sum() + compositionsFailed.sum();
        return count > 0 ? total / count : 0;
    }
    
    private CompositionStats getOrCreateCompositionStats(String compositionName) {
        return compositionStats.computeIfAbsent(compositionName, k -> new CompositionStats());
    }
    
    private void recordDetailedExecution(String compositionName, String compositionId, 
                                       boolean success, Duration executionTime,
                                       int completedSagas, int failedSagas, int skippedSagas) {
        CompositionExecution execution = new CompositionExecution(
            compositionName, compositionId, success, Instant.now(), executionTime,
            completedSagas, failedSagas, skippedSagas
        );
        
        executionsLock.writeLock().lock();
        try {
            recentExecutions.add(execution);
            
            // Maintain size limit
            while (recentExecutions.size() > properties.getMaxStoredResults()) {
                recentExecutions.remove(0);
            }
            
            // Remove old executions based on retention period
            Instant cutoff = Instant.now().minus(properties.getRetentionPeriod());
            recentExecutions.removeIf(exec -> exec.timestamp.isBefore(cutoff));
        } finally {
            executionsLock.writeLock().unlock();
        }
    }
    
    private List<CompositionExecution> getRecentExecutions() {
        executionsLock.readLock().lock();
        try {
            return new ArrayList<>(recentExecutions);
        } finally {
            executionsLock.readLock().unlock();
        }
    }
    
    /**
     * Statistics for a specific composition type.
     */
    public static class CompositionStats {
        public final LongAdder started = new LongAdder();
        public final LongAdder completed = new LongAdder();
        public final LongAdder failed = new LongAdder();
        public final LongAdder compensated = new LongAdder();
        
        private final AtomicLong totalExecutionTime = new AtomicLong(0);
        private final AtomicLong maxExecutionTime = new AtomicLong(0);
        private final AtomicLong minExecutionTime = new AtomicLong(Long.MAX_VALUE);
        
        public void recordExecution(Duration executionTime, boolean success) {
            long millis = executionTime.toMillis();
            totalExecutionTime.addAndGet(millis);
            maxExecutionTime.updateAndGet(current -> Math.max(current, millis));
            minExecutionTime.updateAndGet(current -> Math.min(current, millis));
        }
        
        public double getSuccessRate() {
            long total = completed.sum() + failed.sum();
            return total > 0 ? (double) completed.sum() / total : 0.0;
        }
        
        public long getAverageExecutionTime() {
            long total = completed.sum() + failed.sum();
            return total > 0 ? totalExecutionTime.get() / total : 0;
        }
        
        public long getMaxExecutionTime() { return maxExecutionTime.get(); }
        public long getMinExecutionTime() { 
            return minExecutionTime.get() == Long.MAX_VALUE ? 0 : minExecutionTime.get(); 
        }
    }
    
    /**
     * Timer for tracking active composition execution.
     */
    private static class CompositionTimer {
        public final String compositionName;
        public final Instant startTime;
        
        public CompositionTimer(String compositionName, Instant startTime) {
            this.compositionName = compositionName;
            this.startTime = startTime;
        }
    }
    
    /**
     * Record of a completed composition execution.
     */
    public static class CompositionExecution {
        public final String compositionName;
        public final String compositionId;
        public final boolean success;
        public final Instant timestamp;
        public final Duration executionTime;
        public final int completedSagas;
        public final int failedSagas;
        public final int skippedSagas;
        
        public CompositionExecution(String compositionName, String compositionId, boolean success,
                                  Instant timestamp, Duration executionTime,
                                  int completedSagas, int failedSagas, int skippedSagas) {
            this.compositionName = compositionName;
            this.compositionId = compositionId;
            this.success = success;
            this.timestamp = timestamp;
            this.executionTime = executionTime;
            this.completedSagas = completedSagas;
            this.failedSagas = failedSagas;
            this.skippedSagas = skippedSagas;
        }
    }
}
