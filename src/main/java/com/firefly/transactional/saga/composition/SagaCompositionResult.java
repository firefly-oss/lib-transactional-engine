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

package com.firefly.transactional.saga.composition;

import com.firefly.transactional.saga.core.SagaResult;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Immutable result of a saga composition execution.
 * <p>
 * Provides comprehensive information about the execution of a saga composition,
 * including individual saga results, overall success status, and execution metadata.
 */
public class SagaCompositionResult {
    
    private final String compositionName;
    private final String compositionId;
    private final boolean success;
    private final Instant startedAt;
    private final Instant completedAt;
    private final Map<String, SagaResult> sagaResults;
    private final Set<String> completedSagas;
    private final Set<String> failedSagas;
    private final Set<String> skippedSagas;
    private final Map<String, Throwable> sagaErrors;
    private final Throwable compositionError;
    private final Map<String, Object> sharedVariables;
    
    public SagaCompositionResult(String compositionName,
                                String compositionId,
                                boolean success,
                                Instant startedAt,
                                Instant completedAt,
                                Map<String, SagaResult> sagaResults,
                                Set<String> completedSagas,
                                Set<String> failedSagas,
                                Set<String> skippedSagas,
                                Map<String, Throwable> sagaErrors,
                                Throwable compositionError,
                                Map<String, Object> sharedVariables) {
        this.compositionName = Objects.requireNonNull(compositionName, "compositionName cannot be null");
        this.compositionId = Objects.requireNonNull(compositionId, "compositionId cannot be null");
        this.success = success;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt cannot be null");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt cannot be null");
        this.sagaResults = Collections.unmodifiableMap(new LinkedHashMap<>(sagaResults));
        this.completedSagas = Collections.unmodifiableSet(new HashSet<>(completedSagas));
        this.failedSagas = Collections.unmodifiableSet(new HashSet<>(failedSagas));
        this.skippedSagas = Collections.unmodifiableSet(new HashSet<>(skippedSagas));
        this.sagaErrors = Collections.unmodifiableMap(new HashMap<>(sagaErrors));
        this.compositionError = compositionError;
        this.sharedVariables = Collections.unmodifiableMap(new HashMap<>(sharedVariables));
    }
    
    /**
     * Gets the composition name.
     */
    public String getCompositionName() {
        return compositionName;
    }
    
    /**
     * Gets the composition ID.
     */
    public String getCompositionId() {
        return compositionId;
    }
    
    /**
     * Checks if the composition executed successfully.
     * 
     * @return true if all required sagas completed successfully
     */
    public boolean isSuccess() {
        return success;
    }
    
    /**
     * Gets the composition start time.
     */
    public Instant getStartedAt() {
        return startedAt;
    }
    
    /**
     * Gets the composition completion time.
     */
    public Instant getCompletedAt() {
        return completedAt;
    }
    
    /**
     * Gets the total execution duration.
     */
    public Duration getDuration() {
        return Duration.between(startedAt, completedAt);
    }
    
    /**
     * Gets the results of all executed sagas.
     * 
     * @return an unmodifiable map of saga results by saga ID
     */
    public Map<String, SagaResult> getSagaResults() {
        return sagaResults;
    }
    
    /**
     * Gets the result of a specific saga.
     * 
     * @param sagaId the saga ID
     * @return the saga result, or null if not available
     */
    public SagaResult getSagaResult(String sagaId) {
        return sagaResults.get(sagaId);
    }
    
    /**
     * Gets the set of successfully completed saga IDs.
     */
    public Set<String> getCompletedSagas() {
        return completedSagas;
    }
    
    /**
     * Gets the set of failed saga IDs.
     */
    public Set<String> getFailedSagas() {
        return failedSagas;
    }
    
    /**
     * Gets the set of skipped saga IDs.
     */
    public Set<String> getSkippedSagas() {
        return skippedSagas;
    }
    
    /**
     * Gets errors from failed sagas.
     * 
     * @return an unmodifiable map of errors by saga ID
     */
    public Map<String, Throwable> getSagaErrors() {
        return sagaErrors;
    }
    
    /**
     * Gets the composition-level error, if any.
     * 
     * @return the composition error, or null if none
     */
    public Throwable getCompositionError() {
        return compositionError;
    }
    
    /**
     * Gets the shared variables from the composition execution.
     * 
     * @return an unmodifiable map of shared variables
     */
    public Map<String, Object> getSharedVariables() {
        return sharedVariables;
    }
    
    /**
     * Checks if a specific saga completed successfully.
     * 
     * @param sagaId the saga ID
     * @return true if the saga completed successfully
     */
    public boolean isSagaCompleted(String sagaId) {
        return completedSagas.contains(sagaId);
    }
    
    /**
     * Checks if a specific saga failed.
     * 
     * @param sagaId the saga ID
     * @return true if the saga failed
     */
    public boolean isSagaFailed(String sagaId) {
        return failedSagas.contains(sagaId);
    }
    
    /**
     * Checks if a specific saga was skipped.
     * 
     * @param sagaId the saga ID
     * @return true if the saga was skipped
     */
    public boolean isSagaSkipped(String sagaId) {
        return skippedSagas.contains(sagaId);
    }
    
    /**
     * Gets a value from a specific saga's result.
     * 
     * @param sagaId the saga ID
     * @param stepId the step ID within the saga
     * @return the step result, or null if not available
     */
    public Object getSagaStepResult(String sagaId, String stepId) {
        SagaResult sagaResult = sagaResults.get(sagaId);
        if (sagaResult == null) {
            return null;
        }
        
        SagaResult.StepOutcome outcome = sagaResult.steps().get(stepId);
        return outcome != null ? outcome.result() : null;
    }
    
    /**
     * Gets the total number of sagas in the composition.
     */
    public int getTotalSagaCount() {
        return completedSagas.size() + failedSagas.size() + skippedSagas.size();
    }
    
    /**
     * Gets the number of successfully completed sagas.
     */
    public int getCompletedSagaCount() {
        return completedSagas.size();
    }
    
    /**
     * Gets the number of failed sagas.
     */
    public int getFailedSagaCount() {
        return failedSagas.size();
    }
    
    /**
     * Gets the number of skipped sagas.
     */
    public int getSkippedSagaCount() {
        return skippedSagas.size();
    }
    
    /**
     * Creates a SagaCompositionResult from a composition context.
     * 
     * @param context the composition context
     * @param success the overall success status
     * @param compositionError the composition-level error, if any
     * @return the composition result
     */
    public static SagaCompositionResult from(SagaCompositionContext context, 
                                           boolean success, 
                                           Throwable compositionError) {
        return new SagaCompositionResult(
            context.getCompositionName(),
            context.getCompositionId(),
            success,
            context.getStartedAt(),
            Instant.now(),
            context.getAllSagaResults(),
            context.getCompletedSagas(),
            context.getFailedSagas(),
            context.getSkippedSagas(),
            new HashMap<>(), // saga errors are in the individual results
            compositionError,
            context.getSharedVariables()
        );
    }
}
