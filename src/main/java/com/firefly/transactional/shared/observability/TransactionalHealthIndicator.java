/*
 * Copyright (c) 2023 Firefly Authors. All rights reserved.
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

package com.firefly.transactional.shared.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.util.Map;

/**
 * Spring Boot Actuator health indicator for transactional systems.
 * <p>
 * Provides health status based on transaction execution metrics,
 * success rates, and system performance indicators across all patterns.
 */
public class TransactionalHealthIndicator implements HealthIndicator {

    private final TransactionalMetricsCollector metricsCollector;
    private final TransactionalHealthProperties properties;

    public TransactionalHealthIndicator(TransactionalMetricsCollector metricsCollector, 
                                      TransactionalHealthProperties properties) {
        this.metricsCollector = metricsCollector;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            OverallMetrics metrics = metricsCollector.getOverallMetrics();
            
            Health.Builder builder = new Health.Builder();
            
            // Check overall success rate
            double successRate = metrics.getOverallSuccessRate();
            double stepSuccessRate = metrics.getOverallStepSuccessRate();
            double compensationSuccessRate = metrics.getOverallCompensationSuccessRate();
            
            // Determine health status
            Status status = determineStatus(successRate, stepSuccessRate, compensationSuccessRate);
            builder.status(status);
            
            // Add detailed metrics
            builder.withDetail("overallSuccessRate", String.format("%.2f%%", successRate * 100))
                   .withDetail("stepSuccessRate", String.format("%.2f%%", stepSuccessRate * 100))
                   .withDetail("compensationSuccessRate", String.format("%.2f%%", compensationSuccessRate * 100))
                   .withDetail("totalTransactions", metrics.getTotalTransactionsStarted())
                   .withDetail("totalSteps", metrics.getTotalStepsStarted())
                   .withDetail("totalCompensations", metrics.getTotalCompensationsStarted())
                   .withDetail("averageExecutionTime", String.format("%.2fms", metrics.getOverallAverageExecutionTime()))
                   .withDetail("activeTransactionTypes", metrics.getActiveTransactionTypes());
            
            // Add per-type metrics
            metrics.getMetricsByType().forEach((type, typeMetrics) -> {
                builder.withDetail(type.toLowerCase() + "Metrics", Map.of(
                    "successRate", String.format("%.2f%%", typeMetrics.getSuccessRate() * 100),
                    "transactions", typeMetrics.getTransactionsStarted(),
                    "steps", typeMetrics.getStepsStarted(),
                    "compensations", typeMetrics.getCompensationsStarted()
                ));
            });
            
            return builder.build();
            
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("errorType", e.getClass().getSimpleName())
                    .build();
        }
    }

    private Status determineStatus(double successRate, double stepSuccessRate, double compensationSuccessRate) {
        // If any critical rate is below threshold, system is DOWN
        if (successRate < properties.getCriticalSuccessRateThreshold() ||
            stepSuccessRate < properties.getCriticalSuccessRateThreshold() ||
            compensationSuccessRate < properties.getCriticalSuccessRateThreshold()) {
            return Status.DOWN;
        }
        
        // If any rate is below warning threshold, system is degraded
        if (successRate < properties.getWarningSuccessRateThreshold() ||
            stepSuccessRate < properties.getWarningSuccessRateThreshold() ||
            compensationSuccessRate < properties.getWarningSuccessRateThreshold()) {
            return new Status("DEGRADED");
        }
        
        return Status.UP;
    }

    /**
     * Configuration properties for health checks.
     */
    public static class TransactionalHealthProperties {
        private double warningSuccessRateThreshold = 0.95; // 95%
        private double criticalSuccessRateThreshold = 0.85; // 85%
        
        public double getWarningSuccessRateThreshold() {
            return warningSuccessRateThreshold;
        }
        
        public void setWarningSuccessRateThreshold(double warningSuccessRateThreshold) {
            this.warningSuccessRateThreshold = warningSuccessRateThreshold;
        }
        
        public double getCriticalSuccessRateThreshold() {
            return criticalSuccessRateThreshold;
        }
        
        public void setCriticalSuccessRateThreshold(double criticalSuccessRateThreshold) {
            this.criticalSuccessRateThreshold = criticalSuccessRateThreshold;
        }
    }
}
