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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Shared metrics collector for transactional patterns (SAGA, TCC, etc.).
 * <p>
 * Collects metrics for transaction executions and provides insights into system performance.
 * Tracks execution times, success/failure rates, step performance, and optimization decisions.
 */
@Component
public class TransactionalMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(TransactionalMetricsCollector.class);

    // Transaction execution metrics
    private final Map<String, LongAdder> transactionsStarted = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> transactionsCompleted = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> transactionsFailed = new ConcurrentHashMap<>();
    
    // Step execution metrics
    private final Map<String, LongAdder> stepsStarted = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> stepsCompleted = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> stepsFailed = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> stepsRetried = new ConcurrentHashMap<>();
    
    // Compensation metrics
    private final Map<String, LongAdder> compensationsStarted = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> compensationsCompleted = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> compensationsFailed = new ConcurrentHashMap<>();
    
    // Timing metrics
    private final Map<String, Map<String, Long>> transactionStartTimes = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> totalExecutionTime = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> executionCount = new ConcurrentHashMap<>();

    /**
     * Records that a transaction has started.
     */
    public void recordTransactionStarted(String transactionType, String transactionName, String correlationId) {
        String key = transactionType + ":" + transactionName;
        transactionsStarted.computeIfAbsent(key, k -> new LongAdder()).increment();
        
        // Record start time for duration calculation
        transactionStartTimes.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                .put(correlationId, System.currentTimeMillis());
        
        log.debug("Transaction started: type={}, name={}, correlationId={}", transactionType, transactionName, correlationId);
    }

    /**
     * Records that a transaction has completed.
     */
    public void recordTransactionCompleted(String transactionType, String transactionName, String correlationId, boolean success, long durationMs) {
        String key = transactionType + ":" + transactionName;
        
        if (success) {
            transactionsCompleted.computeIfAbsent(key, k -> new LongAdder()).increment();
        } else {
            transactionsFailed.computeIfAbsent(key, k -> new LongAdder()).increment();
        }
        
        // Record execution time
        if (durationMs > 0) {
            totalExecutionTime.computeIfAbsent(key, k -> new LongAdder()).add(durationMs);
            executionCount.computeIfAbsent(key, k -> new LongAdder()).increment();
        } else {
            // Calculate duration from start time if not provided
            Map<String, Long> startTimes = transactionStartTimes.get(key);
            if (startTimes != null) {
                Long startTime = startTimes.remove(correlationId);
                if (startTime != null) {
                    long calculatedDuration = System.currentTimeMillis() - startTime;
                    totalExecutionTime.computeIfAbsent(key, k -> new LongAdder()).add(calculatedDuration);
                    executionCount.computeIfAbsent(key, k -> new LongAdder()).increment();
                }
            }
        }
        
        log.debug("Transaction completed: type={}, name={}, correlationId={}, success={}, durationMs={}", 
                transactionType, transactionName, correlationId, success, durationMs);
    }

    /**
     * Records that a step has started.
     */
    public void recordStepStarted(String transactionType, String transactionName, String stepId) {
        String key = transactionType + ":" + transactionName + ":" + stepId;
        stepsStarted.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    /**
     * Records that a step has completed successfully.
     */
    public void recordStepCompleted(String transactionType, String transactionName, String stepId, int attempts) {
        String key = transactionType + ":" + transactionName + ":" + stepId;
        stepsCompleted.computeIfAbsent(key, k -> new LongAdder()).increment();
        
        if (attempts > 1) {
            stepsRetried.computeIfAbsent(key, k -> new LongAdder()).add(attempts - 1);
        }
    }

    /**
     * Records that a step has failed.
     */
    public void recordStepFailed(String transactionType, String transactionName, String stepId, int attempts) {
        String key = transactionType + ":" + transactionName + ":" + stepId;
        stepsFailed.computeIfAbsent(key, k -> new LongAdder()).increment();
        
        if (attempts > 1) {
            stepsRetried.computeIfAbsent(key, k -> new LongAdder()).add(attempts - 1);
        }
    }

    /**
     * Records that compensation has started.
     */
    public void recordCompensationStarted(String transactionType, String transactionName, String stepId) {
        String key = transactionType + ":" + transactionName + ":" + stepId;
        compensationsStarted.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    /**
     * Records that compensation has completed.
     */
    public void recordCompensationCompleted(String transactionType, String transactionName, String stepId, boolean success) {
        String key = transactionType + ":" + transactionName + ":" + stepId;
        
        if (success) {
            compensationsCompleted.computeIfAbsent(key, k -> new LongAdder()).increment();
        } else {
            compensationsFailed.computeIfAbsent(key, k -> new LongAdder()).increment();
        }
    }

    /**
     * Gets metrics summary for a specific transaction type.
     */
    public TransactionMetrics getMetrics(String transactionType) {
        return new TransactionMetrics(transactionType, this);
    }

    /**
     * Gets overall metrics across all transaction types.
     */
    public OverallMetrics getOverallMetrics() {
        return new OverallMetrics(this);
    }

    // Package-private getters for metrics classes
    Map<String, LongAdder> getTransactionsStarted() { return transactionsStarted; }
    Map<String, LongAdder> getTransactionsCompleted() { return transactionsCompleted; }
    Map<String, LongAdder> getTransactionsFailed() { return transactionsFailed; }
    Map<String, LongAdder> getStepsStarted() { return stepsStarted; }
    Map<String, LongAdder> getStepsCompleted() { return stepsCompleted; }
    Map<String, LongAdder> getStepsFailed() { return stepsFailed; }
    Map<String, LongAdder> getStepsRetried() { return stepsRetried; }
    Map<String, LongAdder> getCompensationsStarted() { return compensationsStarted; }
    Map<String, LongAdder> getCompensationsCompleted() { return compensationsCompleted; }
    Map<String, LongAdder> getCompensationsFailed() { return compensationsFailed; }
    Map<String, LongAdder> getTotalExecutionTime() { return totalExecutionTime; }
    Map<String, LongAdder> getExecutionCount() { return executionCount; }
}
