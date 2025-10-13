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
import java.util.concurrent.atomic.LongAdder;

/**
 * Metrics for a specific transaction type (e.g., SAGA, TCC).
 */
public class TransactionMetrics {
    
    private final String transactionType;
    private final TransactionalMetricsCollector collector;
    
    public TransactionMetrics(String transactionType, TransactionalMetricsCollector collector) {
        this.transactionType = transactionType;
        this.collector = collector;
    }
    
    public String getTransactionType() {
        return transactionType;
    }
    
    public long getTransactionsStarted() {
        return collector.getTransactionsStarted().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(transactionType + ":"))
                .mapToLong(entry -> entry.getValue().sum())
                .sum();
    }
    
    public long getTransactionsCompleted() {
        return collector.getTransactionsCompleted().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(transactionType + ":"))
                .mapToLong(entry -> entry.getValue().sum())
                .sum();
    }
    
    public long getTransactionsFailed() {
        return collector.getTransactionsFailed().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(transactionType + ":"))
                .mapToLong(entry -> entry.getValue().sum())
                .sum();
    }
    
    public double getSuccessRate() {
        long completed = getTransactionsCompleted();
        long failed = getTransactionsFailed();
        long total = completed + failed;
        return total > 0 ? (double) completed / total : 0.0;
    }
    
    public long getStepsStarted() {
        return collector.getStepsStarted().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(transactionType + ":"))
                .mapToLong(entry -> entry.getValue().sum())
                .sum();
    }
    
    public long getStepsCompleted() {
        return collector.getStepsCompleted().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(transactionType + ":"))
                .mapToLong(entry -> entry.getValue().sum())
                .sum();
    }
    
    public long getStepsFailed() {
        return collector.getStepsFailed().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(transactionType + ":"))
                .mapToLong(entry -> entry.getValue().sum())
                .sum();
    }
    
    public long getStepsRetried() {
        return collector.getStepsRetried().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(transactionType + ":"))
                .mapToLong(entry -> entry.getValue().sum())
                .sum();
    }
    
    public double getStepSuccessRate() {
        long completed = getStepsCompleted();
        long failed = getStepsFailed();
        long total = completed + failed;
        return total > 0 ? (double) completed / total : 0.0;
    }
    
    public long getCompensationsStarted() {
        return collector.getCompensationsStarted().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(transactionType + ":"))
                .mapToLong(entry -> entry.getValue().sum())
                .sum();
    }
    
    public long getCompensationsCompleted() {
        return collector.getCompensationsCompleted().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(transactionType + ":"))
                .mapToLong(entry -> entry.getValue().sum())
                .sum();
    }
    
    public long getCompensationsFailed() {
        return collector.getCompensationsFailed().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(transactionType + ":"))
                .mapToLong(entry -> entry.getValue().sum())
                .sum();
    }
    
    public double getCompensationSuccessRate() {
        long completed = getCompensationsCompleted();
        long failed = getCompensationsFailed();
        long total = completed + failed;
        return total > 0 ? (double) completed / total : 0.0;
    }
    
    public double getAverageExecutionTime() {
        long totalTime = collector.getTotalExecutionTime().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(transactionType + ":"))
                .mapToLong(entry -> entry.getValue().sum())
                .sum();
        
        long count = collector.getExecutionCount().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(transactionType + ":"))
                .mapToLong(entry -> entry.getValue().sum())
                .sum();
        
        return count > 0 ? (double) totalTime / count : 0.0;
    }
    
    public Map<String, Object> toMap() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("transactionType", getTransactionType());
        metrics.put("transactionsStarted", getTransactionsStarted());
        metrics.put("transactionsCompleted", getTransactionsCompleted());
        metrics.put("transactionsFailed", getTransactionsFailed());
        metrics.put("successRate", getSuccessRate());
        metrics.put("stepsStarted", getStepsStarted());
        metrics.put("stepsCompleted", getStepsCompleted());
        metrics.put("stepsFailed", getStepsFailed());
        metrics.put("stepsRetried", getStepsRetried());
        metrics.put("stepSuccessRate", getStepSuccessRate());
        metrics.put("compensationsStarted", getCompensationsStarted());
        metrics.put("compensationsCompleted", getCompensationsCompleted());
        metrics.put("compensationsFailed", getCompensationsFailed());
        metrics.put("compensationSuccessRate", getCompensationSuccessRate());
        metrics.put("averageExecutionTimeMs", getAverageExecutionTime());
        return metrics;
    }
}
