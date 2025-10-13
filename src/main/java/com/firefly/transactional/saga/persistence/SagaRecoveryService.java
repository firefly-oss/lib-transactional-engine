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

package com.firefly.transactional.saga.persistence;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * Service responsible for recovering in-flight sagas after application restarts.
 * <p>
 * This service works with the persistence provider to identify and recover
 * sagas that were interrupted due to application shutdown, crashes, or other
 * infrastructure issues.
 * <p>
 * Key responsibilities:
 * <ul>
 *   <li>Discover in-flight sagas during application startup</li>
 *   <li>Validate and reconstruct saga execution state</li>
 *   <li>Resume saga execution from the last checkpoint</li>
 *   <li>Handle recovery failures and stale saga cleanup</li>
 * </ul>
 */
public interface SagaRecoveryService {

    /**
     * Recovers all in-flight sagas during application startup.
     * This method should be called once during application initialization.
     *
     * @return Mono containing the recovery result with statistics
     */
    Mono<RecoveryResult> recoverInFlightSagas();

    /**
     * Recovers a specific saga by its correlation ID.
     * This can be used for manual recovery or retry scenarios.
     *
     * @param correlationId the saga execution identifier
     * @return Mono containing the recovery result for the specific saga
     */
    Mono<SingleRecoveryResult> recoverSaga(String correlationId);

    /**
     * Identifies and handles stale sagas that haven't been updated recently.
     * Stale sagas may indicate infrastructure issues or long-running processes.
     *
     * @param staleThreshold the time threshold for considering a saga stale
     * @return Flux of stale saga information
     */
    Flux<StaleSagaInfo> identifyStaleSagas(Duration staleThreshold);

    /**
     * Cancels stale sagas that exceed the maximum allowed execution time.
     * This is a safety mechanism to prevent resource leaks.
     *
     * @param maxAge the maximum allowed age for saga executions
     * @return Mono containing the number of cancelled sagas
     */
    Mono<Long> cancelStaleSagas(Duration maxAge);

    /**
     * Validates the integrity of persisted saga state.
     * This can be used for health checks and data validation.
     *
     * @return Mono containing validation results
     */
    Mono<ValidationResult> validatePersistedState();

    /**
     * Result of saga recovery operations.
     */
    class RecoveryResult {
        private final int totalFound;
        private final int successfullyRecovered;
        private final int failed;
        private final int skipped;
        private final Duration recoveryTime;

        public RecoveryResult(int totalFound, int successfullyRecovered, 
                             int failed, int skipped, Duration recoveryTime) {
            this.totalFound = totalFound;
            this.successfullyRecovered = successfullyRecovered;
            this.failed = failed;
            this.skipped = skipped;
            this.recoveryTime = recoveryTime;
        }

        public int getTotalFound() { return totalFound; }
        public int getSuccessfullyRecovered() { return successfullyRecovered; }
        public int getFailed() { return failed; }
        public int getSkipped() { return skipped; }
        public Duration getRecoveryTime() { return recoveryTime; }

        @Override
        public String toString() {
            return String.format("RecoveryResult{total=%d, recovered=%d, failed=%d, skipped=%d, time=%s}",
                    totalFound, successfullyRecovered, failed, skipped, recoveryTime);
        }
    }

    /**
     * Result of single saga recovery.
     */
    class SingleRecoveryResult {
        private final String correlationId;
        private final RecoveryStatus status;
        private final String message;
        private final Throwable error;

        public SingleRecoveryResult(String correlationId, RecoveryStatus status, 
                                   String message, Throwable error) {
            this.correlationId = correlationId;
            this.status = status;
            this.message = message;
            this.error = error;
        }

        public String getCorrelationId() { return correlationId; }
        public RecoveryStatus getStatus() { return status; }
        public String getMessage() { return message; }
        public Throwable getError() { return error; }

        public enum RecoveryStatus {
            SUCCESS, FAILED, SKIPPED, NOT_FOUND
        }
    }

    /**
     * Information about stale sagas.
     */
    class StaleSagaInfo {
        private final String correlationId;
        private final String sagaName;
        private final SagaExecutionStatus status;
        private final Instant lastUpdated;
        private final Duration age;

        public StaleSagaInfo(String correlationId, String sagaName, 
                            SagaExecutionStatus status, Instant lastUpdated, Duration age) {
            this.correlationId = correlationId;
            this.sagaName = sagaName;
            this.status = status;
            this.lastUpdated = lastUpdated;
            this.age = age;
        }

        public String getCorrelationId() { return correlationId; }
        public String getSagaName() { return sagaName; }
        public SagaExecutionStatus getStatus() { return status; }
        public Instant getLastUpdated() { return lastUpdated; }
        public Duration getAge() { return age; }
    }

    /**
     * Result of state validation.
     */
    class ValidationResult {
        private final boolean valid;
        private final int totalChecked;
        private final int corruptedStates;
        private final String details;

        public ValidationResult(boolean valid, int totalChecked, 
                               int corruptedStates, String details) {
            this.valid = valid;
            this.totalChecked = totalChecked;
            this.corruptedStates = corruptedStates;
            this.details = details;
        }

        public boolean isValid() { return valid; }
        public int getTotalChecked() { return totalChecked; }
        public int getCorruptedStates() { return corruptedStates; }
        public String getDetails() { return details; }
    }
}
