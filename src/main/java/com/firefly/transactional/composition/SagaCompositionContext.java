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

package com.firefly.transactional.composition;

import com.firefly.transactional.core.SagaContext;
import com.firefly.transactional.core.SagaResult;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context for saga composition execution, providing cross-saga data sharing
 * and execution state management.
 * <p>
 * This context extends the capabilities of individual SagaContext instances
 * to support coordination between multiple sagas in a composition.
 */
public class SagaCompositionContext {
    
    private final String compositionId;
    private final String compositionName;
    private final SagaContext rootContext;
    private final Instant startedAt;
    
    // Composition-level state
    private final Map<String, SagaResult> sagaResults = new ConcurrentHashMap<>();
    private final Map<String, SagaContext> sagaContexts = new ConcurrentHashMap<>();
    private final Map<String, Object> sharedVariables = new ConcurrentHashMap<>();
    private final Set<String> completedSagas = ConcurrentHashMap.newKeySet();
    private final Set<String> failedSagas = ConcurrentHashMap.newKeySet();
    private final Set<String> skippedSagas = ConcurrentHashMap.newKeySet();
    
    // Execution tracking
    private final Map<String, Instant> sagaStartTimes = new ConcurrentHashMap<>();
    private final Map<String, Instant> sagaEndTimes = new ConcurrentHashMap<>();
    private final Map<String, Throwable> sagaErrors = new ConcurrentHashMap<>();
    
    public SagaCompositionContext(String compositionName, SagaContext rootContext) {
        this.compositionName = Objects.requireNonNull(compositionName, "compositionName cannot be null");
        this.rootContext = Objects.requireNonNull(rootContext, "rootContext cannot be null");
        this.compositionId = rootContext.correlationId() + "-composition";
        this.startedAt = Instant.now();
    }
    
    /**
     * Gets the composition ID.
     */
    public String getCompositionId() {
        return compositionId;
    }
    
    /**
     * Gets the composition name.
     */
    public String getCompositionName() {
        return compositionName;
    }
    
    /**
     * Gets the root saga context.
     */
    public SagaContext getRootContext() {
        return rootContext;
    }
    
    /**
     * Gets the composition start time.
     */
    public Instant getStartedAt() {
        return startedAt;
    }
    
    /**
     * Records the result of a saga execution.
     * 
     * @param sagaId the saga ID
     * @param result the saga result
     * @param context the saga context
     */
    public void recordSagaResult(String sagaId, SagaResult result, SagaContext context) {
        Objects.requireNonNull(sagaId, "sagaId cannot be null");
        Objects.requireNonNull(result, "result cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        
        sagaResults.put(sagaId, result);
        sagaContexts.put(sagaId, context);
        sagaEndTimes.put(sagaId, Instant.now());
        
        if (result.isSuccess()) {
            completedSagas.add(sagaId);
        } else {
            failedSagas.add(sagaId);
            result.error().ifPresent(error -> sagaErrors.put(sagaId, error));
        }
    }
    
    /**
     * Records that a saga was started.
     * 
     * @param sagaId the saga ID
     */
    public void recordSagaStarted(String sagaId) {
        sagaStartTimes.put(sagaId, Instant.now());
    }
    
    /**
     * Records that a saga was skipped.
     * 
     * @param sagaId the saga ID
     */
    public void recordSagaSkipped(String sagaId) {
        skippedSagas.add(sagaId);
        sagaEndTimes.put(sagaId, Instant.now());
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
     * Gets the context of a specific saga.
     * 
     * @param sagaId the saga ID
     * @return the saga context, or null if not available
     */
    public SagaContext getSagaContext(String sagaId) {
        return sagaContexts.get(sagaId);
    }
    
    /**
     * Gets a value from a specific saga's result.
     * 
     * @param sagaId the saga ID
     * @param key the result key
     * @return the result value, or null if not available
     */
    public Object getSagaResultValue(String sagaId, String key) {
        SagaResult result = sagaResults.get(sagaId);
        if (result == null) {
            return null;
        }
        
        SagaResult.StepOutcome outcome = result.steps().get(key);
        return outcome != null ? outcome.result() : null;
    }
    
    /**
     * Sets a shared variable accessible to all sagas in the composition.
     * 
     * @param key the variable key
     * @param value the variable value
     */
    public void setSharedVariable(String key, Object value) {
        sharedVariables.put(key, value);
    }
    
    /**
     * Gets a shared variable.
     * 
     * @param key the variable key
     * @return the variable value, or null if not set
     */
    public Object getSharedVariable(String key) {
        return sharedVariables.get(key);
    }
    
    /**
     * Gets all shared variables.
     * 
     * @return an unmodifiable map of shared variables
     */
    public Map<String, Object> getSharedVariables() {
        return Collections.unmodifiableMap(sharedVariables);
    }
    
    /**
     * Gets the set of completed saga IDs.
     */
    public Set<String> getCompletedSagas() {
        return Collections.unmodifiableSet(completedSagas);
    }
    
    /**
     * Gets the set of failed saga IDs.
     */
    public Set<String> getFailedSagas() {
        return Collections.unmodifiableSet(failedSagas);
    }
    
    /**
     * Gets the set of skipped saga IDs.
     */
    public Set<String> getSkippedSagas() {
        return Collections.unmodifiableSet(skippedSagas);
    }
    
    /**
     * Checks if a saga has completed successfully.
     * 
     * @param sagaId the saga ID
     * @return true if the saga completed successfully
     */
    public boolean isSagaCompleted(String sagaId) {
        return completedSagas.contains(sagaId);
    }
    
    /**
     * Checks if a saga has failed.
     * 
     * @param sagaId the saga ID
     * @return true if the saga failed
     */
    public boolean isSagaFailed(String sagaId) {
        return failedSagas.contains(sagaId);
    }
    
    /**
     * Checks if a saga was skipped.
     * 
     * @param sagaId the saga ID
     * @return true if the saga was skipped
     */
    public boolean isSagaSkipped(String sagaId) {
        return skippedSagas.contains(sagaId);
    }
    
    /**
     * Gets the error for a failed saga.
     * 
     * @param sagaId the saga ID
     * @return the error, or null if no error or saga didn't fail
     */
    public Throwable getSagaError(String sagaId) {
        return sagaErrors.get(sagaId);
    }
    
    /**
     * Gets all saga results.
     * 
     * @return an unmodifiable map of saga results
     */
    public Map<String, SagaResult> getAllSagaResults() {
        return Collections.unmodifiableMap(sagaResults);
    }
}
