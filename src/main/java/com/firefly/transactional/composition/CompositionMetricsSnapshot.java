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

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of composition metrics at a point in time.
 * <p>
 * Provides comprehensive metrics about composition execution performance,
 * success rates, and system health for monitoring and analysis.
 */
public class CompositionMetricsSnapshot {
    
    // Overall composition metrics
    private final long compositionsStarted;
    private final long compositionsCompleted;
    private final long compositionsFailed;
    private final long compositionsCompensated;
    
    // Saga-level metrics within compositions
    private final long sagasExecuted;
    private final long sagasSucceeded;
    private final long sagasFailed;
    private final long sagasSkipped;
    
    // Performance metrics
    private final long averageExecutionTimeMs;
    private final long maxExecutionTimeMs;
    private final long minExecutionTimeMs;
    private final int activeCompositions;
    
    // Detailed metrics by composition type
    private final Map<String, CompositionMetricsCollector.CompositionStats> compositionStats;
    
    // Recent execution history
    private final List<CompositionMetricsCollector.CompositionExecution> recentExecutions;
    
    public CompositionMetricsSnapshot(long compositionsStarted, long compositionsCompleted,
                                    long compositionsFailed, long compositionsCompensated,
                                    long sagasExecuted, long sagasSucceeded,
                                    long sagasFailed, long sagasSkipped,
                                    long averageExecutionTimeMs, long maxExecutionTimeMs,
                                    long minExecutionTimeMs, int activeCompositions,
                                    Map<String, CompositionMetricsCollector.CompositionStats> compositionStats,
                                    List<CompositionMetricsCollector.CompositionExecution> recentExecutions) {
        this.compositionsStarted = compositionsStarted;
        this.compositionsCompleted = compositionsCompleted;
        this.compositionsFailed = compositionsFailed;
        this.compositionsCompensated = compositionsCompensated;
        this.sagasExecuted = sagasExecuted;
        this.sagasSucceeded = sagasSucceeded;
        this.sagasFailed = sagasFailed;
        this.sagasSkipped = sagasSkipped;
        this.averageExecutionTimeMs = averageExecutionTimeMs;
        this.maxExecutionTimeMs = maxExecutionTimeMs;
        this.minExecutionTimeMs = minExecutionTimeMs;
        this.activeCompositions = activeCompositions;
        this.compositionStats = Map.copyOf(compositionStats);
        this.recentExecutions = List.copyOf(recentExecutions);
    }
    
    // Composition-level metrics
    public long getCompositionsStarted() { return compositionsStarted; }
    public long getCompositionsCompleted() { return compositionsCompleted; }
    public long getCompositionsFailed() { return compositionsFailed; }
    public long getCompositionsCompensated() { return compositionsCompensated; }
    
    public long getTotalCompositions() {
        return compositionsCompleted + compositionsFailed;
    }
    
    public double getCompositionSuccessRate() {
        long total = getTotalCompositions();
        return total > 0 ? (double) compositionsCompleted / total : 0.0;
    }
    
    public double getCompositionFailureRate() {
        long total = getTotalCompositions();
        return total > 0 ? (double) compositionsFailed / total : 0.0;
    }
    
    public double getCompensationRate() {
        long total = getTotalCompositions();
        return total > 0 ? (double) compositionsCompensated / total : 0.0;
    }
    
    // Saga-level metrics
    public long getSagasExecuted() { return sagasExecuted; }
    public long getSagasSucceeded() { return sagasSucceeded; }
    public long getSagasFailed() { return sagasFailed; }
    public long getSagasSkipped() { return sagasSkipped; }
    
    public double getSagaSuccessRate() {
        long total = sagasSucceeded + sagasFailed;
        return total > 0 ? (double) sagasSucceeded / total : 0.0;
    }
    
    public double getSagaFailureRate() {
        long total = sagasSucceeded + sagasFailed;
        return total > 0 ? (double) sagasFailed / total : 0.0;
    }
    
    public double getSagaSkipRate() {
        return sagasExecuted > 0 ? (double) sagasSkipped / sagasExecuted : 0.0;
    }
    
    // Performance metrics
    public long getAverageExecutionTimeMs() { return averageExecutionTimeMs; }
    public long getMaxExecutionTimeMs() { return maxExecutionTimeMs; }
    public long getMinExecutionTimeMs() { return minExecutionTimeMs; }
    public int getActiveCompositions() { return activeCompositions; }
    
    // Detailed metrics
    public Map<String, CompositionMetricsCollector.CompositionStats> getCompositionStats() { 
        return compositionStats; 
    }
    
    public List<CompositionMetricsCollector.CompositionExecution> getRecentExecutions() { 
        return recentExecutions; 
    }
    
    /**
     * Gets statistics for a specific composition type.
     */
    public CompositionMetricsCollector.CompositionStats getStatsForComposition(String compositionName) {
        return compositionStats.get(compositionName);
    }
    
    /**
     * Gets the average number of sagas per composition.
     */
    public double getAverageSagasPerComposition() {
        long totalCompositions = getTotalCompositions();
        return totalCompositions > 0 ? (double) sagasExecuted / totalCompositions : 0.0;
    }
    
    /**
     * Gets throughput in compositions per second based on recent executions.
     */
    public double getThroughputPerSecond() {
        if (recentExecutions.isEmpty()) {
            return 0.0;
        }
        
        // Calculate throughput based on last minute of executions
        long oneMinuteAgo = System.currentTimeMillis() - 60000;
        long recentCount = recentExecutions.stream()
                .filter(exec -> exec.timestamp.toEpochMilli() > oneMinuteAgo)
                .count();
        
        return recentCount / 60.0; // per second
    }
    
    /**
     * Checks if the system is healthy based on success rates and performance.
     */
    public boolean isHealthy() {
        return isHealthy(0.95, 0.05); // 95% success rate, max 5% failure rate
    }
    
    /**
     * Checks if the system is healthy based on custom thresholds.
     */
    public boolean isHealthy(double minSuccessRate, double maxFailureRate) {
        double successRate = getCompositionSuccessRate();
        double failureRate = getCompositionFailureRate();
        
        return successRate >= minSuccessRate && failureRate <= maxFailureRate;
    }
    
    /**
     * Gets a summary string of key metrics.
     */
    public String getSummary() {
        return String.format(
            "Compositions: %d started, %d completed (%.1f%% success), %d failed, %d active | " +
            "Sagas: %d executed (%.1f%% success) | " +
            "Performance: avg=%dms, max=%dms, throughput=%.1f/sec",
            compositionsStarted, compositionsCompleted, getCompositionSuccessRate() * 100,
            compositionsFailed, activeCompositions,
            sagasExecuted, getSagaSuccessRate() * 100,
            averageExecutionTimeMs, maxExecutionTimeMs, getThroughputPerSecond()
        );
    }
    
    @Override
    public String toString() {
        return getSummary();
    }
}
