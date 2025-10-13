/*
 * Copyright 2024 Firefly Authors
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

package com.firefly.transactional.tcc.composition;

import com.firefly.transactional.tcc.core.TccContext;
import com.firefly.transactional.tcc.core.TccResult;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context for TCC composition execution, providing cross-TCC data sharing
 * and execution state management.
 * <p>
 * This context extends the capabilities of individual TccContext instances
 * to support coordination between multiple TCC coordinators in a composition.
 * It manages the overall composition state while maintaining individual
 * TCC execution contexts.
 */
public class TccCompositionContext {
    
    private final String compositionId;
    private final String compositionName;
    private final TccContext rootContext;
    private final Instant startedAt;
    
    // Composition-level state
    private final Map<String, TccResult> tccResults = new ConcurrentHashMap<>();
    private final Map<String, TccContext> tccContexts = new ConcurrentHashMap<>();
    private final Map<String, Object> sharedVariables = new ConcurrentHashMap<>();
    private final Set<String> completedTccs = ConcurrentHashMap.newKeySet();
    private final Set<String> failedTccs = ConcurrentHashMap.newKeySet();
    private final Set<String> skippedTccs = ConcurrentHashMap.newKeySet();
    private final Map<String, Throwable> tccErrors = new ConcurrentHashMap<>();
    private final Map<String, Instant> tccStartTimes = new ConcurrentHashMap<>();
    private final Map<String, Instant> tccEndTimes = new ConcurrentHashMap<>();
    
    /**
     * Creates a new TCC composition context.
     * 
     * @param compositionName the name of the composition
     * @param rootContext the root TCC context
     */
    public TccCompositionContext(String compositionName, TccContext rootContext) {
        this.compositionName = Objects.requireNonNull(compositionName, "compositionName cannot be null");
        this.rootContext = Objects.requireNonNull(rootContext, "rootContext cannot be null");
        this.compositionId = rootContext.correlationId() + "-composition";
        this.startedAt = Instant.now();
    }
    
    /**
     * Gets the composition ID.
     * 
     * @return the composition ID
     */
    public String getCompositionId() {
        return compositionId;
    }
    
    /**
     * Gets the composition name.
     * 
     * @return the composition name
     */
    public String getCompositionName() {
        return compositionName;
    }
    
    /**
     * Gets the root TCC context.
     * 
     * @return the root context
     */
    public TccContext getRootContext() {
        return rootContext;
    }
    
    /**
     * Gets the composition start time.
     * 
     * @return the start time
     */
    public Instant getStartedAt() {
        return startedAt;
    }
    
    /**
     * Records the result of a TCC execution.
     * 
     * @param tccId the TCC ID
     * @param result the TCC result
     * @param context the TCC context
     */
    public void recordTccResult(String tccId, TccResult result, TccContext context) {
        Objects.requireNonNull(tccId, "tccId cannot be null");
        Objects.requireNonNull(result, "result cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        
        tccResults.put(tccId, result);
        tccContexts.put(tccId, context);
        tccEndTimes.put(tccId, Instant.now());
        
        if (result.isSuccess()) {
            completedTccs.add(tccId);
        } else {
            failedTccs.add(tccId);
            if (result.getError() != null) {
                tccErrors.put(tccId, result.getError());
            }
        }
    }
    
    /**
     * Records that a TCC was skipped due to execution conditions.
     * 
     * @param tccId the TCC ID
     * @param reason the reason for skipping
     */
    public void recordTccSkipped(String tccId, String reason) {
        Objects.requireNonNull(tccId, "tccId cannot be null");
        skippedTccs.add(tccId);
        tccEndTimes.put(tccId, Instant.now());
    }
    
    /**
     * Marks the start of a TCC execution.
     * 
     * @param tccId the TCC ID
     */
    public void markTccStarted(String tccId) {
        Objects.requireNonNull(tccId, "tccId cannot be null");
        tccStartTimes.put(tccId, Instant.now());
    }
    
    /**
     * Gets a shared variable value.
     * 
     * @param key the variable key
     * @return the variable value, or null if not found
     */
    public Object getSharedVariable(String key) {
        return sharedVariables.get(key);
    }
    
    /**
     * Sets a shared variable value.
     * 
     * @param key the variable key
     * @param value the variable value
     */
    public void setSharedVariable(String key, Object value) {
        Objects.requireNonNull(key, "key cannot be null");
        if (value != null) {
            sharedVariables.put(key, value);
        } else {
            sharedVariables.remove(key);
        }
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
     * Gets the result of a specific TCC.
     * 
     * @param tccId the TCC ID
     * @return the TCC result, or null if not available
     */
    public TccResult getTccResult(String tccId) {
        return tccResults.get(tccId);
    }
    
    /**
     * Gets the context of a specific TCC.
     * 
     * @param tccId the TCC ID
     * @return the TCC context, or null if not available
     */
    public TccContext getTccContext(String tccId) {
        return tccContexts.get(tccId);
    }
    
    /**
     * Gets all TCC results.
     * 
     * @return an unmodifiable map of TCC results
     */
    public Map<String, TccResult> getAllTccResults() {
        return Collections.unmodifiableMap(tccResults);
    }
    
    /**
     * Gets all completed TCC IDs.
     * 
     * @return an unmodifiable set of completed TCC IDs
     */
    public Set<String> getCompletedTccs() {
        return Collections.unmodifiableSet(completedTccs);
    }
    
    /**
     * Gets all failed TCC IDs.
     * 
     * @return an unmodifiable set of failed TCC IDs
     */
    public Set<String> getFailedTccs() {
        return Collections.unmodifiableSet(failedTccs);
    }
    
    /**
     * Gets all skipped TCC IDs.
     * 
     * @return an unmodifiable set of skipped TCC IDs
     */
    public Set<String> getSkippedTccs() {
        return Collections.unmodifiableSet(skippedTccs);
    }
    
    /**
     * Gets all TCC errors.
     * 
     * @return an unmodifiable map of TCC errors
     */
    public Map<String, Throwable> getTccErrors() {
        return Collections.unmodifiableMap(tccErrors);
    }
    
    /**
     * Gets the start time of a specific TCC.
     * 
     * @param tccId the TCC ID
     * @return the start time, or null if not available
     */
    public Instant getTccStartTime(String tccId) {
        return tccStartTimes.get(tccId);
    }
    
    /**
     * Gets the end time of a specific TCC.
     * 
     * @param tccId the TCC ID
     * @return the end time, or null if not available
     */
    public Instant getTccEndTime(String tccId) {
        return tccEndTimes.get(tccId);
    }
}
