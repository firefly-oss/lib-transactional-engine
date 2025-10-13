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

import java.time.Instant;
import java.util.List;

/**
 * Generic persistence provider interface for transactional patterns.
 * <p>
 * This interface provides a common abstraction for persisting transaction state
 * that can be used by both SAGA and TCC patterns. The implementation handles
 * the specific serialization and storage details.
 * 
 * @param <T> the type of execution state (e.g., SagaExecutionState, TccExecutionState)
 */
public interface TransactionalPersistenceProvider<T> {
    
    /**
     * Saves the execution state.
     *
     * @param state the execution state to save
     * @return a Mono that completes when the state is saved
     */
    Mono<Void> saveState(T state);
    
    /**
     * Loads the execution state by correlation ID.
     *
     * @param correlationId the correlation ID
     * @return a Mono containing the execution state, or empty if not found
     */
    Mono<T> loadState(String correlationId);
    
    /**
     * Deletes the execution state by correlation ID.
     *
     * @param correlationId the correlation ID
     * @return a Mono that completes when the state is deleted
     */
    Mono<Void> deleteState(String correlationId);
    
    /**
     * Finds all execution states that were started before the given timestamp.
     * This is useful for recovery scenarios where we need to find stale transactions.
     *
     * @param before the timestamp threshold
     * @return a Flux of execution states
     */
    Flux<T> findStatesStartedBefore(Instant before);
    
    /**
     * Finds all execution states that were last updated before the given timestamp.
     * This is useful for finding transactions that may have stalled.
     *
     * @param before the timestamp threshold
     * @return a Flux of execution states
     */
    Flux<T> findStatesUpdatedBefore(Instant before);
    
    /**
     * Lists all correlation IDs for active transactions.
     * This is useful for monitoring and debugging.
     *
     * @return a Flux of correlation IDs
     */
    Flux<String> listActiveTransactions();
    
    /**
     * Counts the total number of active transactions.
     *
     * @return a Mono containing the count
     */
    Mono<Long> countActiveTransactions();
    
    /**
     * Performs cleanup of expired or completed transactions.
     * This method should be called periodically to prevent storage bloat.
     *
     * @param olderThan transactions older than this timestamp will be cleaned up
     * @return a Mono containing the number of cleaned up transactions
     */
    Mono<Long> cleanup(Instant olderThan);
}
