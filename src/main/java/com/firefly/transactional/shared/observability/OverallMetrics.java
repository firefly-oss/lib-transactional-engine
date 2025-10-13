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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Overall metrics across all transaction types.
 */
public class OverallMetrics {
    
    private final TransactionalMetricsCollector collector;
    
    public OverallMetrics(TransactionalMetricsCollector collector) {
        this.collector = collector;
    }
    
    public long getTotalTransactionsStarted() {
        return collector.getTransactionsStarted().values().stream()
                .mapToLong(adder -> adder.sum())
                .sum();
    }
    
    public long getTotalTransactionsCompleted() {
        return collector.getTransactionsCompleted().values().stream()
                .mapToLong(adder -> adder.sum())
                .sum();
    }
    
    public long getTotalTransactionsFailed() {
        return collector.getTransactionsFailed().values().stream()
                .mapToLong(adder -> adder.sum())
                .sum();
    }
    
    public double getOverallSuccessRate() {
        long completed = getTotalTransactionsCompleted();
        long failed = getTotalTransactionsFailed();
        long total = completed + failed;
        return total > 0 ? (double) completed / total : 0.0;
    }
    
    public long getTotalStepsStarted() {
        return collector.getStepsStarted().values().stream()
                .mapToLong(adder -> adder.sum())
                .sum();
    }
    
    public long getTotalStepsCompleted() {
        return collector.getStepsCompleted().values().stream()
                .mapToLong(adder -> adder.sum())
                .sum();
    }
    
    public long getTotalStepsFailed() {
        return collector.getStepsFailed().values().stream()
                .mapToLong(adder -> adder.sum())
                .sum();
    }
    
    public long getTotalStepsRetried() {
        return collector.getStepsRetried().values().stream()
                .mapToLong(adder -> adder.sum())
                .sum();
    }
    
    public double getOverallStepSuccessRate() {
        long completed = getTotalStepsCompleted();
        long failed = getTotalStepsFailed();
        long total = completed + failed;
        return total > 0 ? (double) completed / total : 0.0;
    }
    
    public long getTotalCompensationsStarted() {
        return collector.getCompensationsStarted().values().stream()
                .mapToLong(adder -> adder.sum())
                .sum();
    }
    
    public long getTotalCompensationsCompleted() {
        return collector.getCompensationsCompleted().values().stream()
                .mapToLong(adder -> adder.sum())
                .sum();
    }
    
    public long getTotalCompensationsFailed() {
        return collector.getCompensationsFailed().values().stream()
                .mapToLong(adder -> adder.sum())
                .sum();
    }
    
    public double getOverallCompensationSuccessRate() {
        long completed = getTotalCompensationsCompleted();
        long failed = getTotalCompensationsFailed();
        long total = completed + failed;
        return total > 0 ? (double) completed / total : 0.0;
    }
    
    public double getOverallAverageExecutionTime() {
        long totalTime = collector.getTotalExecutionTime().values().stream()
                .mapToLong(adder -> adder.sum())
                .sum();
        
        long count = collector.getExecutionCount().values().stream()
                .mapToLong(adder -> adder.sum())
                .sum();
        
        return count > 0 ? (double) totalTime / count : 0.0;
    }
    
    public Set<String> getActiveTransactionTypes() {
        return collector.getTransactionsStarted().keySet().stream()
                .map(key -> key.split(":")[0])
                .collect(Collectors.toSet());
    }
    
    public Map<String, TransactionMetrics> getMetricsByType() {
        return getActiveTransactionTypes().stream()
                .collect(Collectors.toMap(
                    type -> type,
                    type -> new TransactionMetrics(type, collector)
                ));
    }
    
    public Map<String, Object> toMap() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalTransactionsStarted", getTotalTransactionsStarted());
        metrics.put("totalTransactionsCompleted", getTotalTransactionsCompleted());
        metrics.put("totalTransactionsFailed", getTotalTransactionsFailed());
        metrics.put("overallSuccessRate", getOverallSuccessRate());
        metrics.put("totalStepsStarted", getTotalStepsStarted());
        metrics.put("totalStepsCompleted", getTotalStepsCompleted());
        metrics.put("totalStepsFailed", getTotalStepsFailed());
        metrics.put("totalStepsRetried", getTotalStepsRetried());
        metrics.put("overallStepSuccessRate", getOverallStepSuccessRate());
        metrics.put("totalCompensationsStarted", getTotalCompensationsStarted());
        metrics.put("totalCompensationsCompleted", getTotalCompensationsCompleted());
        metrics.put("totalCompensationsFailed", getTotalCompensationsFailed());
        metrics.put("overallCompensationSuccessRate", getOverallCompensationSuccessRate());
        metrics.put("overallAverageExecutionTimeMs", getOverallAverageExecutionTime());
        metrics.put("activeTransactionTypes", getActiveTransactionTypes());
        
        Map<String, Object> byType = new HashMap<>();
        getMetricsByType().forEach((type, typeMetrics) -> byType.put(type, typeMetrics.toMap()));
        metrics.put("metricsByType", byType);
        
        return metrics;
    }
}
