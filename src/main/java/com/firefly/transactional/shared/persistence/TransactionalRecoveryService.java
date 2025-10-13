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

package com.firefly.transactional.shared.persistence;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * Generic recovery service interface for transactional patterns.
 * <p>
 * This interface provides a common abstraction for recovering stalled or
 * incomplete transactions that can be used by both SAGA and TCC patterns.
 * 
 * @param <T> the type of execution state (e.g., SagaExecutionState, TccExecutionState)
 * @param <R> the type of result (e.g., SagaResult, TccResult)
 */
public interface TransactionalRecoveryService<T, R> {
    
    /**
     * Recovers a single transaction by correlation ID.
     *
     * @param correlationId the correlation ID of the transaction to recover
     * @return a Mono containing the recovery result, or empty if transaction not found
     */
    Mono<R> recoverTransaction(String correlationId);
    
    /**
     * Recovers all stalled transactions that haven't been updated within the specified duration.
     *
     * @param stalledDuration transactions not updated within this duration are considered stalled
     * @return a Flux of recovery results
     */
    Flux<R> recoverStalledTransactions(Duration stalledDuration);
    
    /**
     * Recovers all transactions that were started before the given timestamp.
     *
     * @param before the timestamp threshold
     * @return a Flux of recovery results
     */
    Flux<R> recoverTransactionsStartedBefore(Instant before);
    
    /**
     * Checks if a transaction can be recovered.
     *
     * @param state the execution state to check
     * @return true if the transaction can be recovered, false otherwise
     */
    boolean canRecover(T state);
    
    /**
     * Gets the recovery statistics.
     *
     * @return a Mono containing the recovery statistics
     */
    Mono<RecoveryStatistics> getRecoveryStatistics();
    
    /**
     * Performs cleanup of completed or expired transactions.
     *
     * @param olderThan transactions older than this timestamp will be cleaned up
     * @return a Mono containing the number of cleaned up transactions
     */
    Mono<Long> cleanup(Instant olderThan);
    
    /**
     * Statistics about recovery operations.
     */
    class RecoveryStatistics {
        private final long totalRecoveryAttempts;
        private final long successfulRecoveries;
        private final long failedRecoveries;
        private final long stalledTransactions;
        private final Instant lastRecoveryTime;
        
        public RecoveryStatistics(long totalRecoveryAttempts, 
                                long successfulRecoveries, 
                                long failedRecoveries, 
                                long stalledTransactions, 
                                Instant lastRecoveryTime) {
            this.totalRecoveryAttempts = totalRecoveryAttempts;
            this.successfulRecoveries = successfulRecoveries;
            this.failedRecoveries = failedRecoveries;
            this.stalledTransactions = stalledTransactions;
            this.lastRecoveryTime = lastRecoveryTime;
        }
        
        public long getTotalRecoveryAttempts() { return totalRecoveryAttempts; }
        public long getSuccessfulRecoveries() { return successfulRecoveries; }
        public long getFailedRecoveries() { return failedRecoveries; }
        public long getStalledTransactions() { return stalledTransactions; }
        public Instant getLastRecoveryTime() { return lastRecoveryTime; }
        
        public double getSuccessRate() {
            return totalRecoveryAttempts > 0 ? (double) successfulRecoveries / totalRecoveryAttempts : 0.0;
        }
    }
}
