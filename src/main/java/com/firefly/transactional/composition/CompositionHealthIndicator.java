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
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Boot Actuator health indicator for saga composition system.
 * <p>
 * Provides health status based on composition execution metrics,
 * success rates, and system performance indicators.
 */
public class CompositionHealthIndicator implements HealthIndicator {
    
    private final SagaCompositor sagaCompositor;
    private final CompositionMetricsCollector metricsCollector;
    private final SagaCompositionProperties.HealthProperties properties;
    
    // Health thresholds
    private static final double DEFAULT_MIN_SUCCESS_RATE = 0.95; // 95%
    private static final double DEFAULT_MAX_FAILURE_RATE = 0.05; // 5%
    private static final long DEFAULT_MAX_AVG_EXECUTION_TIME = 30000; // 30 seconds
    private static final int DEFAULT_MAX_ACTIVE_COMPOSITIONS = 100;
    
    public CompositionHealthIndicator(SagaCompositor sagaCompositor,
                                    CompositionMetricsCollector metricsCollector,
                                    SagaCompositionProperties.HealthProperties properties) {
        this.sagaCompositor = sagaCompositor;
        this.metricsCollector = metricsCollector;
        this.properties = properties;
    }
    
    @Override
    public Health health() {
        try {
            CompositionMetricsSnapshot metrics = metricsCollector.getMetrics();
            
            Health.Builder builder = new Health.Builder();
            Map<String, Object> details = new HashMap<>();
            
            // Overall health status
            Status status = determineOverallHealth(metrics);
            builder.status(status);
            
            // Add basic metrics
            details.put("compositionsStarted", metrics.getCompositionsStarted());
            details.put("compositionsCompleted", metrics.getCompositionsCompleted());
            details.put("compositionsFailed", metrics.getCompositionsFailed());
            details.put("activeCompositions", metrics.getActiveCompositions());
            
            // Add success rates
            details.put("compositionSuccessRate", String.format("%.2f%%", metrics.getCompositionSuccessRate() * 100));
            details.put("sagaSuccessRate", String.format("%.2f%%", metrics.getSagaSuccessRate() * 100));
            
            // Add performance metrics
            details.put("averageExecutionTimeMs", metrics.getAverageExecutionTimeMs());
            details.put("maxExecutionTimeMs", metrics.getMaxExecutionTimeMs());
            details.put("throughputPerSecond", String.format("%.2f", metrics.getThroughputPerSecond()));
            
            // Add saga-level metrics
            details.put("sagasExecuted", metrics.getSagasExecuted());
            details.put("sagasSucceeded", metrics.getSagasSucceeded());
            details.put("sagasFailed", metrics.getSagasFailed());
            details.put("sagasSkipped", metrics.getSagasSkipped());
            
            // Add health checks
            Map<String, Object> healthChecks = new HashMap<>();
            healthChecks.put("successRateCheck", checkSuccessRate(metrics));
            healthChecks.put("performanceCheck", checkPerformance(metrics));
            healthChecks.put("loadCheck", checkSystemLoad(metrics));
            healthChecks.put("errorRateCheck", checkErrorRate(metrics));
            details.put("healthChecks", healthChecks);
            
            // Add composition-specific stats if available
            if (!metrics.getCompositionStats().isEmpty()) {
                Map<String, Object> compositionDetails = new HashMap<>();
                metrics.getCompositionStats().forEach((name, stats) -> {
                    Map<String, Object> statDetails = new HashMap<>();
                    statDetails.put("started", stats.started.sum());
                    statDetails.put("completed", stats.completed.sum());
                    statDetails.put("failed", stats.failed.sum());
                    statDetails.put("successRate", String.format("%.2f%%", stats.getSuccessRate() * 100));
                    statDetails.put("averageExecutionTimeMs", stats.getAverageExecutionTime());
                    compositionDetails.put(name, statDetails);
                });
                details.put("compositionStats", compositionDetails);
            }
            
            // Add warnings if any
            addWarnings(details, metrics);
            
            return builder.withDetails(details).build();
            
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", "Failed to collect health metrics: " + e.getMessage())
                    .build();
        }
    }
    
    private Status determineOverallHealth(CompositionMetricsSnapshot metrics) {
        // Check if we have enough data to make a determination
        if (metrics.getTotalCompositions() < 10) {
            return Status.UP; // Not enough data, assume healthy
        }
        
        // Check critical health indicators
        boolean successRateOk = metrics.getCompositionSuccessRate() >= DEFAULT_MIN_SUCCESS_RATE;
        boolean failureRateOk = metrics.getCompositionFailureRate() <= DEFAULT_MAX_FAILURE_RATE;
        boolean performanceOk = metrics.getAverageExecutionTimeMs() <= DEFAULT_MAX_AVG_EXECUTION_TIME;
        boolean loadOk = metrics.getActiveCompositions() <= DEFAULT_MAX_ACTIVE_COMPOSITIONS;
        
        // Determine status based on checks
        if (successRateOk && failureRateOk && performanceOk && loadOk) {
            return Status.UP;
        } else if (metrics.getCompositionSuccessRate() >= 0.8 && metrics.getCompositionFailureRate() <= 0.2) {
            return Status.UP; // Degraded but still functional
        } else {
            return Status.DOWN;
        }
    }
    
    private Map<String, Object> checkSuccessRate(CompositionMetricsSnapshot metrics) {
        Map<String, Object> check = new HashMap<>();
        double successRate = metrics.getCompositionSuccessRate();
        
        check.put("value", String.format("%.2f%%", successRate * 100));
        check.put("threshold", String.format("%.2f%%", DEFAULT_MIN_SUCCESS_RATE * 100));
        check.put("status", successRate >= DEFAULT_MIN_SUCCESS_RATE ? "PASS" : "FAIL");
        
        if (successRate < DEFAULT_MIN_SUCCESS_RATE) {
            check.put("message", "Success rate below threshold");
        }
        
        return check;
    }
    
    private Map<String, Object> checkPerformance(CompositionMetricsSnapshot metrics) {
        Map<String, Object> check = new HashMap<>();
        long avgTime = metrics.getAverageExecutionTimeMs();
        
        check.put("averageExecutionTimeMs", avgTime);
        check.put("thresholdMs", DEFAULT_MAX_AVG_EXECUTION_TIME);
        check.put("status", avgTime <= DEFAULT_MAX_AVG_EXECUTION_TIME ? "PASS" : "FAIL");
        
        if (avgTime > DEFAULT_MAX_AVG_EXECUTION_TIME) {
            check.put("message", "Average execution time exceeds threshold");
        }
        
        return check;
    }
    
    private Map<String, Object> checkSystemLoad(CompositionMetricsSnapshot metrics) {
        Map<String, Object> check = new HashMap<>();
        int activeCompositions = metrics.getActiveCompositions();
        
        check.put("activeCompositions", activeCompositions);
        check.put("threshold", DEFAULT_MAX_ACTIVE_COMPOSITIONS);
        check.put("status", activeCompositions <= DEFAULT_MAX_ACTIVE_COMPOSITIONS ? "PASS" : "FAIL");
        
        if (activeCompositions > DEFAULT_MAX_ACTIVE_COMPOSITIONS) {
            check.put("message", "Too many active compositions");
        }
        
        return check;
    }
    
    private Map<String, Object> checkErrorRate(CompositionMetricsSnapshot metrics) {
        Map<String, Object> check = new HashMap<>();
        double failureRate = metrics.getCompositionFailureRate();
        
        check.put("value", String.format("%.2f%%", failureRate * 100));
        check.put("threshold", String.format("%.2f%%", DEFAULT_MAX_FAILURE_RATE * 100));
        check.put("status", failureRate <= DEFAULT_MAX_FAILURE_RATE ? "PASS" : "FAIL");
        
        if (failureRate > DEFAULT_MAX_FAILURE_RATE) {
            check.put("message", "Failure rate exceeds threshold");
        }
        
        return check;
    }
    
    private void addWarnings(Map<String, Object> details, CompositionMetricsSnapshot metrics) {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        
        // Check for performance warnings
        if (metrics.getMaxExecutionTimeMs() > DEFAULT_MAX_AVG_EXECUTION_TIME * 2) {
            warnings.add("Some compositions are taking very long to execute");
        }
        
        // Check for high skip rate
        if (metrics.getSagaSkipRate() > 0.1) { // 10%
            warnings.add("High saga skip rate detected - check execution conditions");
        }
        
        // Check for compensation rate
        if (metrics.getCompensationRate() > 0.05) { // 5%
            warnings.add("High compensation rate - check for frequent failures");
        }
        
        // Check for low throughput
        if (metrics.getThroughputPerSecond() < 0.1 && metrics.getTotalCompositions() > 10) {
            warnings.add("Low throughput detected");
        }
        
        if (!warnings.isEmpty()) {
            details.put("warnings", warnings);
        }
    }
}
