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

package com.firefly.transactional.persistence;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Core abstraction for saga state persistence.
 * <p>
 * This interface provides the contract for persisting and recovering saga execution state.
 * Implementations can be in-memory (default, zero-persistence) or external storage like Redis.
 * <p>
 * Key responsibilities:
 * <ul>
 *   <li>Persist saga execution state at checkpoints</li>
 *   <li>Recover in-flight sagas after application restarts</li>
 *   <li>Manage saga lifecycle and cleanup</li>
 *   <li>Support both synchronous and asynchronous operations</li>
 * </ul>
 * <p>
 * Thread-safety: All implementations must be thread-safe for concurrent access.
 */
public interface SagaPersistenceProvider {

    /**
     * Persists the current state of a saga execution.
     * This method is called at key checkpoints during saga execution.
     *
     * @param sagaState the complete saga state to persist
     * @return Mono that completes when the state is successfully persisted
     */
    Mono<Void> persistSagaState(SagaExecutionState sagaState);

    /**
     * Retrieves the persisted state for a specific saga execution.
     *
     * @param correlationId the unique identifier for the saga execution
     * @return Mono containing the saga state if found, empty if not found
     */
    Mono<Optional<SagaExecutionState>> getSagaState(String correlationId);

    /**
     * Updates the status of a specific step within a saga execution.
     * This is used for incremental updates without persisting the entire state.
     *
     * @param correlationId the saga execution identifier
     * @param stepId the step identifier
     * @param status the new step status
     * @return Mono that completes when the status is updated
     */
    Mono<Void> updateStepStatus(String correlationId, String stepId, StepExecutionStatus status);

    /**
     * Marks a saga execution as completed and schedules it for cleanup.
     *
     * @param correlationId the saga execution identifier
     * @param successful whether the saga completed successfully
     * @return Mono that completes when the saga is marked as completed
     */
    Mono<Void> markSagaCompleted(String correlationId, boolean successful);

    /**
     * Retrieves all in-flight (incomplete) saga executions.
     * This is used during application startup to recover interrupted sagas.
     *
     * @return Flux of all in-flight saga states
     */
    Flux<SagaExecutionState> getInFlightSagas();

    /**
     * Retrieves in-flight sagas that were last updated before the specified time.
     * This is useful for finding stale sagas that may need intervention.
     *
     * @param before the cutoff time
     * @return Flux of stale saga states
     */
    Flux<SagaExecutionState> getStaleSagas(Instant before);

    /**
     * Removes persisted state for completed sagas older than the specified duration.
     * This is used for cleanup and storage management.
     *
     * @param olderThan the age threshold for cleanup
     * @return Mono containing the number of cleaned up saga states
     */
    Mono<Long> cleanupCompletedSagas(Duration olderThan);

    /**
     * Checks if the persistence provider is healthy and available.
     *
     * @return Mono containing true if healthy, false otherwise
     */
    Mono<Boolean> isHealthy();

    /**
     * Gets the type of this persistence provider for configuration and logging purposes.
     *
     * @return the provider type
     */
    PersistenceProviderType getProviderType();

    /**
     * Enumeration of supported persistence provider types.
     */
    enum PersistenceProviderType {
        /**
         * In-memory persistence (default, zero-persistence behavior).
         */
        IN_MEMORY,

        /**
         * Redis-based persistence.
         */
        REDIS,

        /**
         * Database-based persistence (future extension).
         */
        DATABASE,

        /**
         * Custom persistence implementation.
         */
        CUSTOM
    }
}
