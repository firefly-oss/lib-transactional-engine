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

package com.firefly.transactional.persistence.impl;

import com.firefly.transactional.persistence.SagaExecutionState;
import com.firefly.transactional.persistence.SagaPersistenceProvider;
import com.firefly.transactional.persistence.StepExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory implementation of SagaPersistenceProvider.
 * <p>
 * This implementation provides the default zero-persistence behavior that maintains
 * backward compatibility with the existing transactional engine. It stores saga
 * state in memory only and does not provide true persistence across application restarts.
 * <p>
 * Key characteristics:
 * <ul>
 *   <li>Zero external dependencies</li>
 *   <li>No configuration required</li>
 *   <li>Fast performance for development and testing</li>
 *   <li>No persistence across application restarts</li>
 *   <li>Memory usage grows with active sagas</li>
 * </ul>
 * <p>
 * This provider is suitable for:
 * <ul>
 *   <li>Development and testing environments</li>
 *   <li>Short-lived sagas that complete quickly</li>
 *   <li>Applications where persistence is not required</li>
 *   <li>Backward compatibility with existing deployments</li>
 * </ul>
 */
public class InMemorySagaPersistenceProvider implements SagaPersistenceProvider {

    private static final Logger log = LoggerFactory.getLogger(InMemorySagaPersistenceProvider.class);

    private final ConcurrentMap<String, SagaExecutionState> sagaStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Instant> completedSagas = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> persistSagaState(SagaExecutionState sagaState) {
        return Mono.fromRunnable(() -> {
            String correlationId = sagaState.getCorrelationId();
            log.debug("Persisting saga state in memory for correlation ID: {}", correlationId);
            
            sagaStates.put(correlationId, sagaState);
            
            // If saga is completed, track completion time for cleanup
            if (sagaState.getStatus().isCompleted()) {
                completedSagas.put(correlationId, Instant.now());
            }
        });
    }

    @Override
    public Mono<Optional<SagaExecutionState>> getSagaState(String correlationId) {
        return Mono.fromCallable(() -> {
            log.debug("Retrieving saga state from memory for correlation ID: {}", correlationId);
            SagaExecutionState state = sagaStates.get(correlationId);
            return Optional.ofNullable(state);
        });
    }

    @Override
    public Mono<Void> updateStepStatus(String correlationId, String stepId, StepExecutionStatus status) {
        return Mono.fromRunnable(() -> {
            log.debug("Updating step status in memory for correlation ID: {}, step: {}, status: {}", 
                    correlationId, stepId, status.getStatus());
            
            SagaExecutionState currentState = sagaStates.get(correlationId);
            if (currentState != null) {
                SagaExecutionState updatedState = currentState.withStepStatus(stepId, status);
                sagaStates.put(correlationId, updatedState);
            } else {
                log.warn("Attempted to update step status for non-existent saga: {}", correlationId);
            }
        });
    }

    @Override
    public Mono<Void> markSagaCompleted(String correlationId, boolean successful) {
        return Mono.fromRunnable(() -> {
            log.debug("Marking saga as completed in memory for correlation ID: {}, successful: {}", 
                    correlationId, successful);
            
            SagaExecutionState currentState = sagaStates.get(correlationId);
            if (currentState != null) {
                // Update the saga status based on success/failure
                var newStatus = successful ? 
                    com.firefly.transactional.persistence.SagaExecutionStatus.COMPLETED_SUCCESS :
                    com.firefly.transactional.persistence.SagaExecutionStatus.FAILED;
                
                SagaExecutionState updatedState = currentState.withStatus(newStatus);
                sagaStates.put(correlationId, updatedState);
                completedSagas.put(correlationId, Instant.now());
            } else {
                log.warn("Attempted to mark non-existent saga as completed: {}", correlationId);
            }
        });
    }

    @Override
    public Flux<SagaExecutionState> getInFlightSagas() {
        return Flux.fromIterable(sagaStates.values())
                .filter(SagaExecutionState::isInFlight)
                .doOnSubscribe(subscription -> 
                    log.debug("Retrieving in-flight sagas from memory, total states: {}", sagaStates.size()));
    }

    @Override
    public Flux<SagaExecutionState> getStaleSagas(Instant before) {
        return Flux.fromIterable(sagaStates.values())
                .filter(state -> state.isStale(before))
                .doOnSubscribe(subscription -> 
                    log.debug("Retrieving stale sagas from memory before: {}", before));
    }

    @Override
    public Mono<Long> cleanupCompletedSagas(Duration olderThan) {
        return Mono.fromCallable(() -> {
            Instant cutoff = Instant.now().minus(olderThan);
            log.debug("Cleaning up completed sagas older than: {}", cutoff);
            
            long cleanedCount = 0;
            
            // Find completed sagas older than the cutoff
            var toRemove = completedSagas.entrySet().stream()
                    .filter(entry -> entry.getValue().isBefore(cutoff))
                    .map(entry -> entry.getKey())
                    .toList();
            
            // Remove from both maps
            for (String correlationId : toRemove) {
                sagaStates.remove(correlationId);
                completedSagas.remove(correlationId);
                cleanedCount++;
            }
            
            log.debug("Cleaned up {} completed saga states from memory", cleanedCount);
            return cleanedCount;
        });
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return Mono.fromCallable(() -> {
            // In-memory provider is always healthy if the application is running
            boolean healthy = true;
            log.debug("In-memory persistence provider health check: healthy={}, active_sagas={}", 
                    healthy, sagaStates.size());
            return healthy;
        });
    }

    @Override
    public PersistenceProviderType getProviderType() {
        return PersistenceProviderType.IN_MEMORY;
    }

    /**
     * Gets the current number of saga states in memory.
     * This method is useful for monitoring and testing.
     *
     * @return the number of saga states currently stored
     */
    public int getActiveSagaCount() {
        return sagaStates.size();
    }

    /**
     * Gets the current number of completed sagas tracked for cleanup.
     * This method is useful for monitoring and testing.
     *
     * @return the number of completed sagas tracked
     */
    public int getCompletedSagaCount() {
        return completedSagas.size();
    }

    /**
     * Clears all saga states from memory.
     * This method is primarily for testing purposes.
     */
    public void clear() {
        log.debug("Clearing all saga states from memory");
        sagaStates.clear();
        completedSagas.clear();
    }

    /**
     * Gets memory usage statistics for monitoring.
     *
     * @return memory usage information
     */
    public MemoryUsageInfo getMemoryUsage() {
        return new MemoryUsageInfo(
                sagaStates.size(),
                completedSagas.size(),
                estimateMemoryUsage()
        );
    }

    /**
     * Estimates the memory usage of stored saga states.
     * This is a rough estimate for monitoring purposes.
     */
    private long estimateMemoryUsage() {
        // Rough estimation: each saga state entry uses approximately 1KB
        // This is a simplified calculation for monitoring purposes
        return (long) sagaStates.size() * 1024 + (long) completedSagas.size() * 64;
    }

    /**
     * Memory usage information for the in-memory provider.
     */
    public static class MemoryUsageInfo {
        private final int activeSagas;
        private final int completedSagas;
        private final long estimatedBytes;

        public MemoryUsageInfo(int activeSagas, int completedSagas, long estimatedBytes) {
            this.activeSagas = activeSagas;
            this.completedSagas = completedSagas;
            this.estimatedBytes = estimatedBytes;
        }

        public int getActiveSagas() { return activeSagas; }
        public int getCompletedSagas() { return completedSagas; }
        public long getEstimatedBytes() { return estimatedBytes; }

        @Override
        public String toString() {
            return String.format("MemoryUsage{active=%d, completed=%d, bytes=%d}", 
                    activeSagas, completedSagas, estimatedBytes);
        }
    }
}
