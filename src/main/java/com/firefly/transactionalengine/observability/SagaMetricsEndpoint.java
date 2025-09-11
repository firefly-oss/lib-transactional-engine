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

package com.firefly.transactionalengine.observability;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Boot Actuator endpoint for exposing saga metrics.
 * Available at /actuator/saga-metrics when Spring Boot Actuator is on the classpath.
 */
@Component
@Endpoint(id = "saga-metrics")
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
public class SagaMetricsEndpoint {
    
    private final SagaMetricsCollector metricsCollector;
    
    public SagaMetricsEndpoint(SagaMetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }
    
    /**
     * Returns current saga metrics.
     */
    @ReadOperation
    public Map<String, Object> metrics() {
        SagaMetricsCollector.MetricsSnapshot snapshot = metricsCollector.getMetrics();
        
        Map<String, Object> metrics = new HashMap<>();
        
        // Saga metrics
        Map<String, Object> sagaMetrics = new HashMap<>();
        sagaMetrics.put("started", snapshot.getSagasStarted());
        sagaMetrics.put("completed", snapshot.getSagasCompleted());
        sagaMetrics.put("failed", snapshot.getSagasFailed());
        sagaMetrics.put("compensated", snapshot.getSagasCompensated());
        sagaMetrics.put("active", snapshot.getActiveSagas());
        sagaMetrics.put("successRate", String.format("%.2f%%", snapshot.getSagaSuccessRate() * 100));
        metrics.put("sagas", sagaMetrics);
        
        // Step metrics
        Map<String, Object> stepMetrics = new HashMap<>();
        stepMetrics.put("executed", snapshot.getStepsExecuted());
        stepMetrics.put("succeeded", snapshot.getStepsSucceeded());
        stepMetrics.put("failed", snapshot.getStepsFailed());
        stepMetrics.put("compensated", snapshot.getStepsCompensated());
        stepMetrics.put("active", snapshot.getActiveSteps());
        stepMetrics.put("successRate", String.format("%.2f%%", snapshot.getStepSuccessRate() * 100));
        metrics.put("steps", stepMetrics);
        
        // Performance metrics
        Map<String, Object> performanceMetrics = new HashMap<>();
        performanceMetrics.put("averageExecutionTimeMs", snapshot.getAverageExecutionTimeMs());
        performanceMetrics.put("maxExecutionTimeMs", snapshot.getMaxExecutionTimeMs());
        performanceMetrics.put("minExecutionTimeMs", snapshot.getMinExecutionTimeMs());
        metrics.put("performance", performanceMetrics);
        
        // Optimization metrics
        Map<String, Object> optimizationMetrics = new HashMap<>();
        optimizationMetrics.put("optimizedContextsCreated", snapshot.getOptimizedContextsCreated());
        optimizationMetrics.put("standardContextsCreated", snapshot.getStandardContextsCreated());
        optimizationMetrics.put("optimizationRate", String.format("%.2f%%", snapshot.getOptimizationRate() * 100));
        optimizationMetrics.put("backpressureApplications", snapshot.getBackpressureApplications());
        metrics.put("optimization", optimizationMetrics);
        
        return metrics;
    }
    
    /**
     * Resets all metrics (useful for testing or clearing historical data).
     */
    @WriteOperation
    public Map<String, String> reset() {
        metricsCollector.reset();
        return Map.of("status", "Metrics reset successfully");
    }
}
